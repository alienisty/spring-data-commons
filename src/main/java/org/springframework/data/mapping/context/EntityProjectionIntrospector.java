/*
 * Copyright 2021 the original author or authors.
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
package org.springframework.data.mapping.context;

import java.beans.PropertyDescriptor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.data.mapping.PersistentEntity;
import org.springframework.data.mapping.PersistentProperty;
import org.springframework.data.mapping.PropertyPath;
import org.springframework.data.projection.ProjectionFactory;
import org.springframework.data.projection.ProjectionInformation;
import org.springframework.data.util.ClassTypeInformation;
import org.springframework.data.util.TypeInformation;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;

/**
 * This class is introspects the returned type in the context of a domain type for all reachable properties (w/o cycles)
 * to determine which property paths are subject to projection.
 *
 * @author Gerrit Meier
 * @author Mark Paluch
 * @since 2.7
 */
public class EntityProjectionIntrospector {

	private final ProjectionFactory projectionFactory;
	private final ProjectionPredicate projectionPredicate;
	private final MappingContext<?, ?> mappingContext;

	private EntityProjectionIntrospector(ProjectionFactory projectionFactory, ProjectionPredicate projectionPredicate,
			MappingContext<?, ?> mappingContext) {
		this.projectionFactory = projectionFactory;
		this.projectionPredicate = projectionPredicate;
		this.mappingContext = mappingContext;
	}

	/**
	 * Create a new {@link EntityProjectionIntrospector} given {@link ProjectionFactory}, {@link ProjectionPredicate} and
	 * {@link MappingContext}.
	 *
	 * @param projectionFactory must not be {@literal null}.
	 * @param projectionPredicate must not be {@literal null}.
	 * @param mappingContext must not be {@literal null}.
	 * @return a new {@link EntityProjectionIntrospector} instance.
	 */
	public static EntityProjectionIntrospector create(ProjectionFactory projectionFactory,
			ProjectionPredicate projectionPredicate, MappingContext<?, ?> mappingContext) {

		Assert.notNull(projectionFactory, "ProjectionFactory must not be null");
		Assert.notNull(projectionPredicate, "ProjectionPredicate must not be null");
		Assert.notNull(mappingContext, "MappingContext must not be null");

		return new EntityProjectionIntrospector(projectionFactory, projectionPredicate, mappingContext);
	}

	/**
	 * Introspect a {@link Class mapped type} in the context of a {@link Class domain type} whether the returned type is a
	 * projection and what property paths are participating in the projection.
	 * <p>
	 * Nested properties (direct types, within maps, collections) are introspected for nested projections and contain
	 * property paths for closed projections.
	 *
	 * @param mappedType
	 * @param domainType
	 * @return
	 */
	public <M, D> EntityProjection<M, D> introspect(Class<M> mappedType, Class<D> domainType) {

		ClassTypeInformation<M> returnedTypeInformation = ClassTypeInformation.from(mappedType);
		ClassTypeInformation<D> domainTypeInformation = ClassTypeInformation.from(domainType);

		boolean isProjection = projectionPredicate.test(mappedType, domainType);

		if (!isProjection) {
			return EntityProjection.nonProjecting(returnedTypeInformation, domainTypeInformation, Collections.emptyList());
		}

		ProjectionInformation projectionInformation = projectionFactory.getProjectionInformation(mappedType);

		if (!projectionInformation.isClosed()) {
			return EntityProjection.projecting(returnedTypeInformation, domainTypeInformation, Collections.emptyList(),
					false);
		}

		PersistentEntity<?, ?> persistentEntity = mappingContext.getRequiredPersistentEntity(domainType);
		List<EntityProjection.PropertyProjection<?, ?>> propertyDescriptors = getProperties(null, projectionInformation,
				returnedTypeInformation,
				persistentEntity, null);

		return EntityProjection.projecting(returnedTypeInformation, domainTypeInformation, propertyDescriptors, true);
	}

	private List<EntityProjection.PropertyProjection<?, ?>> getProperties(@Nullable PropertyPath propertyPath,
			ProjectionInformation projectionInformation, TypeInformation<?> projectionTypeInformation,
			PersistentEntity<?, ?> persistentEntity, @Nullable CycleGuard cycleGuard) {

		List<EntityProjection.PropertyProjection<?, ?>> propertyDescriptors = new ArrayList<>();

		// TODO: PropertyDescriptor only created for DTO's with getters/setters
		for (PropertyDescriptor inputProperty : projectionInformation.getInputProperties()) {

			PersistentProperty<?> persistentProperty = persistentEntity.getPersistentProperty(inputProperty.getName());

			if (persistentProperty == null) {
				continue;
			}

			CycleGuard cycleGuardToUse = cycleGuard != null ? cycleGuard : new CycleGuard();

			TypeInformation<?> property = projectionTypeInformation.getRequiredProperty(inputProperty.getName());

			PropertyPath nestedPropertyPath = propertyPath == null
					? PropertyPath.from(persistentProperty.getName(), persistentEntity.getTypeInformation())
					: propertyPath.nested(persistentProperty.getName());

			TypeInformation<?> returnedType = property.getRequiredActualType();
			TypeInformation<?> domainType = persistentProperty.getTypeInformation().getRequiredActualType();

			if (isProjection(returnedType, domainType)) {

				List<EntityProjection.PropertyProjection<?, ?>> nestedPropertyDescriptors;

				if (cycleGuardToUse.isCycleFree(persistentProperty)) {
					nestedPropertyDescriptors = getProjectedProperties(nestedPropertyPath, returnedType, domainType,
							cycleGuardToUse);
				} else {
					nestedPropertyDescriptors = Collections.emptyList();
				}

				propertyDescriptors.add(EntityProjection.PropertyProjection.projecting(nestedPropertyPath, property,
						persistentProperty.getTypeInformation(),
						nestedPropertyDescriptors, projectionInformation.isClosed()));
			} else {
				propertyDescriptors
						.add(EntityProjection.PropertyProjection.nonProjecting(nestedPropertyPath, property,
								persistentProperty.getTypeInformation()));
			}
		}

		return propertyDescriptors;
	}

	private boolean isProjection(TypeInformation<?> returnedType, TypeInformation<?> domainType) {
		return projectionPredicate.test(returnedType.getRequiredActualType().getType(),
				domainType.getRequiredActualType().getType());
	}

	private List<EntityProjection.PropertyProjection<?, ?>> getProjectedProperties(PropertyPath propertyPath,
			TypeInformation<?> returnedType, TypeInformation<?> domainType, CycleGuard cycleGuard) {

		ProjectionInformation projectionInformation = projectionFactory.getProjectionInformation(returnedType.getType());
		PersistentEntity<?, ?> persistentEntity = mappingContext.getRequiredPersistentEntity(domainType);

		// Closed projection should get handled as above (recursion)
		return projectionInformation.isClosed()
				? getProperties(propertyPath, projectionInformation, returnedType, persistentEntity, cycleGuard)
				: Collections.emptyList();
	}

	/**
	 * Represents a predicate (boolean-valued function) of a {@link Class target type} and its {@link Class underlying
	 * type}.
	 */
	public interface ProjectionPredicate {

		/**
		 * Evaluates this predicate on the given arguments.
		 *
		 * @param target the target type.
		 * @param target the underlying type.
		 * @return {@code true} if the input argument matches the predicate, otherwise {@code false}.
		 */
		boolean test(Class<?> target, Class<?> underlyingType);

		/**
		 * Return a composed predicate that represents a short-circuiting logical AND of this predicate and another. When
		 * evaluating the composed predicate, if this predicate is {@code false}, then the {@code other} predicate is not
		 * evaluated.
		 * <p>
		 * Any exceptions thrown during evaluation of either predicate are relayed to the caller; if evaluation of this
		 * predicate throws an exception, the {@code other} predicate will not be evaluated.
		 *
		 * @param other a predicate that will be logically-ANDed with this predicate
		 * @return a composed predicate that represents the short-circuiting logical AND of this predicate and the
		 *         {@code other} predicate
		 */
		default ProjectionPredicate and(ProjectionPredicate other) {
			return (target, underlyingType) -> test(target, underlyingType) && other.test(target, underlyingType);
		}

		/**
		 * Return a predicate that represents the logical negation of this predicate.
		 *
		 * @return a predicate that represents the logical negation of this predicate
		 */
		default ProjectionPredicate negate() {
			return (target, underlyingType) -> !test(target, underlyingType);
		}

		/**
		 * Return a predicate that considers whether the {@code target type} is participating in the type hierarchy.
		 */
		static ProjectionPredicate typeHierarchy() {

			ProjectionPredicate predicate = (target, underlyingType) -> target.isAssignableFrom(underlyingType) || // hierarchy
					underlyingType.isAssignableFrom(target);
			return predicate.negate();
		}

	}

	static class CycleGuard {
		Set<PersistentProperty<?>> seen = new LinkedHashSet<>();

		public boolean isCycleFree(PersistentProperty<?> property) {
			return seen.add(property);
		}
	}

}