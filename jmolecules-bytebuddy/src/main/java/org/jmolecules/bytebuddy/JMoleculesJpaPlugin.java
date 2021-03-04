/*
 * Copyright 2020-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jmolecules.bytebuddy;

import static net.bytebuddy.matcher.ElementMatchers.*;

import lombok.extern.slf4j.Slf4j;
import net.bytebuddy.build.Plugin;
import net.bytebuddy.description.annotation.AnnotationDescription;
import net.bytebuddy.description.annotation.AnnotationList;
import net.bytebuddy.description.annotation.AnnotationSource;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.method.MethodDescription.InGenericShape;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.description.type.TypeDescription.Generic;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType.Builder;
import net.bytebuddy.implementation.MethodCall;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.matcher.ElementMatcher;

import java.io.IOException;
import java.io.Serializable;
import java.lang.annotation.Annotation;
import java.util.Collection;
import java.util.Iterator;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Stream;

import javax.persistence.*;

import org.jmolecules.ddd.types.AggregateRoot;
import org.jmolecules.ddd.types.Association;
import org.jmolecules.ddd.types.Entity;
import org.jmolecules.ddd.types.Identifier;
import org.jmolecules.jpa.JMoleculesJpa;

@Slf4j
public class JMoleculesJpaPlugin implements Plugin {

	static final String NULLABILITY_METHOD_NAME = "__verifyNullability";

	@Override
	public boolean matches(TypeDescription target) {

		if (target.isAnnotation()) {
			return false;
		}

		if (!target.getInterfaces().filter(nameStartsWith("org.jmolecules")).isEmpty()) {
			return true;
		}

		Generic superType = target.getSuperClass();

		return superType == null || superType.represents(Object.class) ? false : matches(superType.asErasure());
	}

	/*
	 * (non-Javadoc)
	 * @see net.bytebuddy.build.Plugin#apply(net.bytebuddy.dynamic.DynamicType.Builder, net.bytebuddy.description.type.TypeDescription, net.bytebuddy.dynamic.ClassFileLocator)
	 */
	@Override
	public Builder<?> apply(Builder<?> builder, TypeDescription type, ClassFileLocator classFileLocator) {

		boolean isAggregateRoot = type.isAssignableTo(AggregateRoot.class)
				|| hasAnnotation(type, org.jmolecules.ddd.annotation.AggregateRoot.class);

		if (isAggregateRoot) {
			builder = handleAggregateRoot(builder, type);
		}

		if (type.isAssignableTo(Entity.class)
				|| hasAnnotation(type, org.jmolecules.ddd.annotation.Entity.class)) {
			builder = handleEntity(builder, type, isAggregateRoot);
		}

		if (type.isAssignableTo(Association.class)) {
			builder = handleAssociation(builder, type);
		}

		if (type.isAssignableTo(Identifier.class)) {
			builder = handleIdentifier(builder, type);
		}

		return builder;
	}

	/*
	 * (non-Javadoc)
	 * @see java.io.Closeable#close()
	 */
	@Override
	public void close() throws IOException {}

	private static Builder<?> handleAggregateRoot(Builder<?> builder, TypeDescription type) {

		// Default entity references to OneToOne mapping
		AnnotationDescription oneToOneDescription = createCascadingAnnotation(OneToOne.class);

		builder = builder.field(wrap(fieldType(isEntity(type))
				.and(not(hasJpaRelationShipAnnotation())), oneToOneDescription))
				.annotateField(oneToOneDescription);

		// Default collection entity references to @OneToMany mapping
		AnnotationDescription oneToManyDescription = createCascadingAnnotation(OneToMany.class);

		builder = builder.field(wrap(genericFieldType(isCollectionOfEntity(type))
				.and(not(hasJpaRelationShipAnnotation())), oneToManyDescription))
				.annotateField(oneToManyDescription);

		return builder;
	}

	private static <T extends AnnotationSource> ElementMatcher.Junction<T> hasJpaRelationShipAnnotation() {

		return isAnnotatedWith(OneToOne.class)
				.or(isAnnotatedWith(OneToMany.class))
				.or(isAnnotatedWith(ManyToOne.class))
				.or(isAnnotatedWith(ManyToMany.class));
	}

	private static ElementMatcher<FieldDescription> wrap(Junction<FieldDescription> source,
			AnnotationDescription annotation) {

		return it -> {

			boolean matches = source.matches(it);

			if (matches) {

				String ownerName = it.getDeclaringType().asErasure().getSimpleName();

				log.info("jMolecules JPA Plugin - {} - Defaulting {}.{} to {} mapping.",
						ownerName, ownerName, it.getName(), annotation);
			}

			return matches;
		};
	}

	private static ElementMatcher<TypeDescription> isEntity(TypeDescription reference) {

		return it -> {

			boolean match = it.isAssignableTo(Entity.class)
					|| hasAnnotation(it, org.jmolecules.ddd.annotation.Entity.class);

			return match;
		};
	}

	private static ElementMatcher<? super Generic> isCollectionOfEntity(TypeDescription reference) {

		return it -> {

			boolean match = it.asErasure().isAssignableTo(Collection.class)
					&& isEntity(reference).matches(it.asGenericType().getTypeArguments().get(0).asErasure());

			return match;
		};
	}

	private static boolean hasAnnotation(TypeDescription type, Class<? extends Annotation> annotation) {

		Objects.requireNonNull(type, "Type must not be null!");

		AnnotationList found = type.getDeclaredAnnotations();

		if (found.isAnnotationPresent(annotation)) {
			return true;
		}

		if (found.isEmpty()) {
			return false;
		}

		return found.asTypeList() //
				.stream() //
				.filter(it -> !it.getPackage().getName().startsWith("java")) //
				.anyMatch(it -> hasAnnotation(it, annotation));
	}

	private static Builder<?> handleIdentifier(Builder<?> builder, TypeDescription type) {

		if (!type.isAssignableTo(Serializable.class)) {

			log.info("jMolecules JPA Plugin - {} - Implement Serializable.", type.getSimpleName());

			builder = builder.implement(Serializable.class);
		}

		builder = addDefaultConstructorIfMissing(builder, type);

		return addAnnotationIfMissing(Embeddable.class, builder, type, Embeddable.class);
	}

	private static Builder<?> handleAssociation(Builder<?> builder, TypeDescription type) {

		builder = addDefaultConstructorIfMissing(builder, type);

		return addAnnotationIfMissing(Embeddable.class, builder, type, Embeddable.class);
	}

	private static Builder<?> handleEntity(Builder<?> builder, TypeDescription type, boolean forAggregateRoot) {

		// builder = declareNullVerificationMethod(builder, type);
		builder = addDefaultConstructorIfMissing(builder, type);

		// Annotate identifier types
		AnnotationDescription annotation = createAnnotation(EmbeddedId.class);

		builder = builder
				.field(wrap(fieldType(isSubTypeOf(Identifier.class))
						.and(not(isAnnotatedWith(EmbeddedId.class).or(isAnnotatedWith(Id.class)))), annotation))
				.annotateField(annotation);

		Function<TypeDescription, Class<? extends Annotation>> selector = it -> !forAggregateRoot && type.isAbstract()
				? MappedSuperclass.class
				: javax.persistence.Entity.class;

		return addAnnotationIfMissing(selector, builder, type, javax.persistence.Entity.class,
				MappedSuperclass.class);
	}

	@SafeVarargs
	private static Builder<?> addAnnotationIfMissing(Class<? extends Annotation> annotation, Builder<?> builder,
			TypeDescription type, Class<? extends Annotation>... exclusions) {
		return addAnnotationIfMissing(__ -> annotation, builder, type, exclusions);
	}

	@SafeVarargs
	private static Builder<?> addAnnotationIfMissing(Function<TypeDescription, Class<? extends Annotation>> producer,
			Builder<?> builder,
			TypeDescription type, Class<? extends Annotation>... exclusions) {

		AnnotationList existing = type.getDeclaredAnnotations();
		Class<? extends Annotation> annotation = producer.apply(type);

		boolean existingFound = Stream.of(exclusions).anyMatch(it -> {

			boolean found = existing.isAnnotationPresent(it);

			if (found) {
				log.info("jMolecules JPA Plugin - {} - Not adding @{} because type is already annotated with @{}.",
						type.getSimpleName(), annotation.getSimpleName(), it.getSimpleName());
			}

			return found;
		});

		if (existingFound) {
			return builder;
		}

		log.info("jMolecules JPA Plugin - {} - Adding @{}.", type.getSimpleName(), annotation.getName());

		return builder.annotateType(createAnnotation(annotation));
	}

	private static AnnotationDescription createAnnotation(Class<? extends Annotation> type) {
		return AnnotationDescription.Builder.ofType(type).build();
	}

	private static AnnotationDescription createCascadingAnnotation(Class<? extends Annotation> type) {
		return AnnotationDescription.Builder.ofType(type)
				.defineEnumerationArray("cascade", CascadeType.class, CascadeType.ALL)
				.build();
	}

	@SuppressWarnings("unused")
	private static Builder<?> declareNullVerificationMethod(Builder<?> builder, TypeDescription type) {

		String typeName = type.getSimpleName();

		if (type.isAbstract()) {
			log.info("jMolecules JPA Plugin - {} - Not generating nullability method for abstract type.", typeName);
			return builder;
		}

		if (type.getDeclaredMethods().filter(it -> it.getName().equals(NULLABILITY_METHOD_NAME)).size() > 0) {
			log.info("jMolecules JPA Plugin - {} - Found existing nullability method.", typeName);
			return builder;
		}

		log.info("jMolecules JPA Plugin - {} - Adding nullability verification method.", typeName);

		return builder.defineMethod(NULLABILITY_METHOD_NAME, void.class, Visibility.PACKAGE_PRIVATE)
				.intercept(MethodDelegation.to(JMoleculesJpa.class))
				.annotateMethod(createAnnotation(PrePersist.class))
				.annotateMethod(createAnnotation(PostLoad.class));
	}

	private static Builder<?> addDefaultConstructorIfMissing(Builder<?> builder, TypeDescription type) {

		boolean hasDefaultConstructor = !type.getDeclaredMethods()
				.filter(it -> it.isConstructor())
				.filter(it -> it.getParameters().size() == 0)
				.isEmpty();

		if (hasDefaultConstructor) {

			log.info("jMolecules JPA Plugin - {} - Default constructor already present.", type.getSimpleName());

			return builder;
		}

		Generic superClass = type.getSuperClass();
		Iterator<InGenericShape> superClassConstructors = superClass.getDeclaredMethods()
				.filter(it -> it.isConstructor())
				.filter(it -> it.getParameters().size() == 0).iterator();

		InGenericShape superClassConstructor = superClassConstructors.hasNext() ? superClassConstructors.next() : null;

		if (superClassConstructor == null) {
			log.info(
					"jMolecules JPA Plugin - {} - No default constructor found on superclass {}. Skipping default constructor creation.",
					type.getName(), superClass.asErasure().getName());
		}

		log.info("jMolecules JPA Plugin - {} - Adding default constructor.", type.getSimpleName());

		return builder.defineConstructor(Visibility.PUBLIC)
				.intercept(MethodCall.invoke(superClassConstructor));
	}
}
