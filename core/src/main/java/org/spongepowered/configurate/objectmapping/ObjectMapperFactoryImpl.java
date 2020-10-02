/*
 * Configurate
 * Copyright (C) zml and Configurate contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.spongepowered.configurate.objectmapping;

import static io.leangen.geantyref.GenericTypeReflector.annotate;
import static io.leangen.geantyref.GenericTypeReflector.box;
import static io.leangen.geantyref.GenericTypeReflector.erase;
import static io.leangen.geantyref.GenericTypeReflector.isMissingTypeParameters;
import static io.leangen.geantyref.GenericTypeReflector.isSuperType;
import static java.util.Objects.requireNonNull;

import io.leangen.geantyref.GenericTypeReflector;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.objectmapping.meta.Comment;
import org.spongepowered.configurate.objectmapping.meta.Constraint;
import org.spongepowered.configurate.objectmapping.meta.Matches;
import org.spongepowered.configurate.objectmapping.meta.NodeResolver;
import org.spongepowered.configurate.objectmapping.meta.Processor;
import org.spongepowered.configurate.objectmapping.meta.Required;
import org.spongepowered.configurate.serialize.TypeSerializer;
import org.spongepowered.configurate.util.CheckedFunction;
import org.spongepowered.configurate.util.NamingScheme;
import org.spongepowered.configurate.util.NamingSchemes;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Factory for a basic {@link ObjectMapper}.
 */
final class ObjectMapperFactoryImpl implements ObjectMapper.Factory, TypeSerializer<Object> {

    private static final int MAXIMUM_MAPPERS_SIZE = 64;

    private final Map<Type, ObjectMapper<?>> mappers = new LinkedHashMap<Type, ObjectMapper<?>>() {
        @Override
        protected boolean removeEldestEntry(final Map.Entry<Type, ObjectMapper<?>> eldest) {
            return this.size() > MAXIMUM_MAPPERS_SIZE;
        }
    };
    private final List<NodeResolver.Factory> resolverFactories;
    private final List<FieldDiscoverer<?>> fieldDiscoverers;
    private final Map<Class<? extends Annotation>, List<Definition<?, ?, ? extends Constraint.Factory<?, ?>>>> constraints;
    private final Map<Class<? extends Annotation>, List<Definition<?, ?, ? extends Processor.Factory<?, ?>>>> processors;

    ObjectMapperFactoryImpl(final Builder builder) {
        this.resolverFactories = new ArrayList<>(builder.resolvers);
        Collections.reverse(this.resolverFactories);

        // Apply the naming scheme-based resolver with lowest priority
        final @Nullable NamingScheme scheme = builder.namingScheme;
        if (scheme != null) {
            this.resolverFactories.add((name, element) -> {
                final String key = scheme.coerce(name);
                return node -> node.getNode(key);
            });
        }

        this.fieldDiscoverers = new ArrayList<>(builder.discoverer);
        Collections.reverse(this.fieldDiscoverers);
        this.constraints = new HashMap<>();
        for (Definition<?, ?, ? extends Constraint.Factory<?, ?>> def : builder.constraints) {
            this.constraints.computeIfAbsent(def.annotation(), k -> new ArrayList<>()).add(def);
        }
        this.constraints.values().forEach(Collections::reverse);

        this.processors = new HashMap<>();
        for (Definition<?, ?, ? extends Processor.Factory<?, ?>> def : builder.processors) {
            this.processors.computeIfAbsent(def.annotation(), k -> new ArrayList<>()).add(def);
        }
        this.processors.values().forEach(Collections::reverse);
    }

    @Override
    public ObjectMapper<?> get(final Type type) throws ObjectMappingException {
        requireNonNull(type, "type");
        if (isMissingTypeParameters(type)) {
            throw new ObjectMappingException("Raw types are not supported!");
        }

        synchronized (this.mappers) {
            return computeFromMap(this.mappers, type, this::computeMapper);
        }
    }

    @Override
    public TypeSerializer<Object> asTypeSerializer() {
        return this;
    }

    private ObjectMapper<?> computeMapper(final Type type) throws ObjectMappingException {
        for (FieldDiscoverer<?> discoverer : this.fieldDiscoverers) {
            final @Nullable ObjectMapper<?> result = newMapper(type, discoverer);
            if (result != null) {
                return result;
            }
        }

        throw new ObjectMappingException("Could not find factory for type " + type);
    }

    private <I, V> @Nullable ObjectMapper<V> newMapper(final Type type, final FieldDiscoverer<I> discoverer) throws ObjectMappingException {
        final List<FieldData<I, V>> fields = new ArrayList<>();
        final FieldDiscoverer.@Nullable InstanceFactory<I> candidate = discoverer.<V>discover(annotate(type),
            (name, fieldType, container, deserializer, serializer) -> makeData(fields, name, fieldType, container, deserializer, serializer));

        if (candidate == null) {
            return null;
        }

        if (candidate instanceof FieldDiscoverer.MutableInstanceFactory<?>) {
            return new ObjectMapperImpl.Mutable<>(type, fields, ((FieldDiscoverer.MutableInstanceFactory<I>) candidate));
        } else {
            return new ObjectMapperImpl<>(type, fields, candidate);
        }
    }

    /**
     * Build a field data object by calculating the appropriate metadata.
     *
     * @param name field name
     * @param type field type
     * @param container element containing the field
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private <I, O> void makeData(final List<FieldData<I, O>> fields, final String name, final AnnotatedType type,
            final AnnotatedElement container, final BiConsumer<I, Object> deserializer, final CheckedFunction<O, Object, Exception> serializer) {
        @Nullable NodeResolver resolver = null;
        for (NodeResolver.Factory factory : this.resolverFactories) {
            final @Nullable NodeResolver next = factory.make(name, container);
            if (next != null) {
                if (next != NodeResolver.SKIP_FIELD) {
                    resolver = next;
                }
                break;
            }
        }

        if (resolver == null) {
            return;
        }

        final Type normalizedType = box(type.getType());
        final List<Constraint<?>> constraints = new ArrayList<>();
        final List<Processor<?>> processors = new ArrayList<>();
        for (Annotation annotation : container.getAnnotations()) {
            final List<Definition<?, ?, ? extends Constraint.Factory<?, ?>>> definitions = this.constraints.get(annotation.annotationType());
            if (definitions != null) {
                for (final Definition<?, ?, ? extends Constraint.Factory<?, ?>> def : definitions) {
                    if (isSuperType(def.type(), normalizedType)) {
                        constraints.add(((Constraint.Factory) def.factory()).make(annotation, type.getType()));
                    }
                }
            }

            final List<Definition<?, ?, ? extends Processor.Factory<?, ?>>> processorDefs = this.processors.get(annotation.annotationType());
            if (processorDefs != null) {
                for (final Definition<?, ?, ? extends Processor.Factory<?, ?>> processorDef : processorDefs) {
                    if (isSuperType(processorDef.type(), normalizedType)) {
                        processors.add(((Processor.Factory) processorDef.factory()).make(annotation, type.getType()));
                    }
                }
            }
        }

        fields.add(FieldData.of(name, type, constraints, processors, deserializer, serializer, resolver));
    }

    // TypeSerializer //

    public static final String CLASS_KEY = "__class__";

    @Override
    public Object deserialize(final Type type, final ConfigurationNode node) throws ObjectMappingException {
        final Type clazz = getInstantiableType(type, node.getNode(CLASS_KEY).getString());
        return get(clazz).load(node);
    }

    private Type getInstantiableType(final Type type, final @Nullable String configuredName) throws ObjectMappingException {
        final Type retClass;
        final Class<?> rawType = erase(type);
        if (rawType.isInterface() || Modifier.isAbstract(rawType.getModifiers())) {
            if (configuredName == null) {
                throw new ObjectMappingException("No available configured type for instances of " + type);
            } else {
                try {
                    retClass = Class.forName(configuredName);
                } catch (final ClassNotFoundException e) {
                    throw new ObjectMappingException("Unknown class of object " + configuredName, e);
                }
                if (!GenericTypeReflector.isSuperType(type, retClass)) {
                    throw new ObjectMappingException("Configured type " + configuredName + " does not extend "
                            + rawType.getCanonicalName());
                }
            }
        } else {
            retClass = type;
        }
        return retClass;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void serialize(final Type type, final @Nullable Object obj, final ConfigurationNode node) throws ObjectMappingException {
        if (obj == null) {
            final ConfigurationNode clazz = node.getNode(CLASS_KEY);
            node.setValue(null);
            if (!clazz.isVirtual()) {
                node.getNode(CLASS_KEY).setValue(clazz);
            }
            return;
        }
        final Class<?> rawType = erase(type);
        final ObjectMapper<?> mapper;
        if (rawType.isInterface() || Modifier.isAbstract(rawType.getModifiers())) {
            // serialize obj's concrete type rather than the interface/abstract class
            node.getNode(CLASS_KEY).setValue(obj.getClass().getName());
            mapper = get(obj.getClass());
        } else {
            mapper = get(type);
        }
        ((ObjectMapper<Object>) mapper).save(obj, node);
    }

    // Helpers to get value from map

    /**
     * Lightweight wrapper exception that doesn't need to track its
     * own stacktrace.
     */
    private static class StacklessWrapper extends RuntimeException {

        StacklessWrapper(final Throwable cause) {
            super(cause);
        }

        @Override
        public synchronized Throwable fillInStackTrace() {
            // no-op
            return this;
        }
    }

    @SuppressWarnings("unchecked") // for E cast
    private static <K, V, E extends Exception> V computeFromMap(final Map<K, V> map, final K key, final CheckedFunction<K, V, E> creator) throws E {
        try {
            return map.computeIfAbsent(key, k -> {
                try {
                    return creator.apply(k);
                } catch (final Exception e) {
                    if (e instanceof RuntimeException) {
                        throw (RuntimeException) e;
                    } else {
                        throw new StacklessWrapper(e);
                    }
                }
            });
        } catch (final StacklessWrapper ex) {
            throw (E) ex.getCause();
        }
    }

    static ObjectMapper.Factory.Builder defaultBuilder() {
        return new Builder()
                .setDefaultNamingScheme(NamingSchemes.LOWER_CASE_DASHED)
                // Resolvers //
                .addNodeResolver(NodeResolver.nodeKey())
                .addNodeResolver(NodeResolver.keyFromSetting())
                // Constraints and processors //
                .addProcessor(Comment.class, Processor.comments())
                .addConstraint(Matches.class, String.class, Constraint.pattern())
                .addConstraint(Required.class, Constraint.required())
                // Field discovers //
                .addDiscoverer(FieldDiscoverer.ofEmptyConstructorObject())
                .addDiscoverer(FieldDiscoverer.ofRecord());
    }

    /**
     * A factory with default options.
     */
    static final ObjectMapper.Factory INSTANCE = defaultBuilder().build();

    static class Builder implements ObjectMapper.Factory.Builder {

        private @Nullable NamingScheme namingScheme;
        private final List<NodeResolver.Factory> resolvers = new ArrayList<>();
        private final List<FieldDiscoverer<?>> discoverer = new ArrayList<>();
        private final List<Definition<?, ?, ? extends Constraint.Factory<?, ?>>> constraints = new ArrayList<>();
        private final List<Definition<?, ?, ? extends Processor.Factory<?, ?>>> processors = new ArrayList<>();

        @Override
        public ObjectMapper.Factory.Builder setDefaultNamingScheme(final NamingScheme scheme) {
            this.namingScheme = scheme;
            return this;
        }

        @Override
        public Builder addNodeResolver(final NodeResolver.Factory resolver) {
            this.resolvers.add(resolver);
            return this;
        }

        @Override
        public Builder addDiscoverer(final FieldDiscoverer<?> discoverer) {
            this.discoverer.add(discoverer);
            return this;
        }

        @Override
        public <A extends Annotation, T> Builder addProcessor(final Class<A> definition, final Class<T> valueType,
                final Processor.Factory<A, T> factory) {
            this.processors.add(Definition.of(definition, valueType, factory));
            return this;
        }

        @Override
        public <A extends Annotation, T> Builder addConstraint(final Class<A> definition, final Class<T> valueType,
                final Constraint.Factory<A, T> factory) {
            this.constraints.add(Definition.of(definition, valueType, factory));
            return this;
        }

        @Override
        public ObjectMapper.Factory build() {
            return new ObjectMapperFactoryImpl(this);
        }

    }

}
