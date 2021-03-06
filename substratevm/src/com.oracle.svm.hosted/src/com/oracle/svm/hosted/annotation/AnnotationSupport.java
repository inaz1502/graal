/*
 * Copyright (c) 2014, 2017, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.svm.hosted.annotation;

import java.lang.annotation.Annotation;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.graalvm.compiler.api.replacements.SnippetReflectionProvider;
import org.graalvm.compiler.core.common.type.StampFactory;
import org.graalvm.compiler.core.common.type.StampPair;
import org.graalvm.compiler.core.common.type.TypeReference;
import org.graalvm.compiler.debug.DebugContext;
import org.graalvm.compiler.java.FrameStateBuilder;
import org.graalvm.compiler.nodes.CallTargetNode.InvokeKind;
import org.graalvm.compiler.nodes.ConstantNode;
import org.graalvm.compiler.nodes.FixedGuardNode;
import org.graalvm.compiler.nodes.LogicConstantNode;
import org.graalvm.compiler.nodes.LogicNode;
import org.graalvm.compiler.nodes.PiNode;
import org.graalvm.compiler.nodes.ReturnNode;
import org.graalvm.compiler.nodes.StructuredGraph;
import org.graalvm.compiler.nodes.UnwindNode;
import org.graalvm.compiler.nodes.ValueNode;
import org.graalvm.compiler.nodes.calc.AddNode;
import org.graalvm.compiler.nodes.calc.IntegerEqualsNode;
import org.graalvm.compiler.nodes.calc.ObjectEqualsNode;
import org.graalvm.compiler.nodes.calc.XorNode;
import org.graalvm.compiler.nodes.extended.BoxNode;
import org.graalvm.compiler.nodes.extended.BranchProbabilityNode;
import org.graalvm.compiler.nodes.java.ArrayLengthNode;
import org.graalvm.compiler.nodes.java.InstanceOfNode;
import org.graalvm.compiler.nodes.java.LoadFieldNode;
import org.graalvm.compiler.replacements.GraphKit;
import org.graalvm.compiler.replacements.nodes.BasicObjectCloneNode;
import org.graalvm.nativeimage.ImageSingletons;
import org.graalvm.nativeimage.hosted.Feature;

import com.oracle.graal.pointsto.constraints.UnsupportedFeatureException;
import com.oracle.graal.pointsto.meta.HostedProviders;
import com.oracle.svm.core.annotate.AutomaticFeature;
import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;
import com.oracle.svm.core.hub.AnnotationTypeSupport;
import com.oracle.svm.core.jdk.AnnotationSupportConfig;
import com.oracle.svm.core.meta.SubstrateObjectConstant;
import com.oracle.svm.core.util.VMError;
import com.oracle.svm.hosted.FeatureImpl.DuringSetupAccessImpl;
import com.oracle.svm.hosted.analysis.Inflation;
import com.oracle.svm.hosted.phases.HostedGraphKit;
import com.oracle.svm.hosted.snippets.SubstrateGraphBuilderPlugins;

import jdk.vm.ci.meta.DeoptimizationAction;
import jdk.vm.ci.meta.DeoptimizationReason;
import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.JavaType;
import jdk.vm.ci.meta.MetaAccessProvider;
import jdk.vm.ci.meta.ResolvedJavaField;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.ResolvedJavaType;
import sun.reflect.annotation.AnnotationType;
import sun.reflect.annotation.TypeNotPresentExceptionProxy;

public class AnnotationSupport extends CustomSubstitution<AnnotationSubstitutionType> {

    private final SnippetReflectionProvider snippetReflection;

    private final ResolvedJavaType javaLangAnnotationAnnotation;
    private final ResolvedJavaType javaLangReflectProxy;
    private final ResolvedJavaType constantAnnotationMarker;

    public AnnotationSupport(MetaAccessProvider metaAccess, SnippetReflectionProvider snippetReflection) {
        super(metaAccess);
        this.snippetReflection = snippetReflection;

        javaLangAnnotationAnnotation = metaAccess.lookupJavaType(java.lang.annotation.Annotation.class);
        javaLangReflectProxy = metaAccess.lookupJavaType(java.lang.reflect.Proxy.class);
        constantAnnotationMarker = metaAccess.lookupJavaType(ConstantAnnotationMarker.class);

        AnnotationSupportConfig.initialize();
    }

    private boolean isConstantAnnotationType(ResolvedJavaType type) {
        /*
         * Check if the type implements all of Annotation, Proxy and ConstantAnnotationMarker. If
         * so, then it is the type of a annotation proxy object encountered during heap scanning.
         * Only those types are substituted with a more efficient annotation proxy type
         * implementation. If a type implements only Annotation and Proxy but not
         * ConstantAnnotationMarker then it is a proxy type registered via the dynamic proxy API.
         * Such type is used to allocate annotation instances at run time and must not be replaced.
         */
        return javaLangAnnotationAnnotation.isAssignableFrom(type) && javaLangReflectProxy.isAssignableFrom(type) &&
                        constantAnnotationMarker.isAssignableFrom(type);
    }

    @Override
    public ResolvedJavaType lookup(ResolvedJavaType type) {
        if (isConstantAnnotationType(type)) {
            return getSubstitution(type);
        }
        return type;
    }

    @Override
    public ResolvedJavaType resolve(ResolvedJavaType type) {
        if (type instanceof AnnotationSubstitutionType) {
            return ((AnnotationSubstitutionType) type).original;
        }
        return type;
    }

    @Override
    public ResolvedJavaField lookup(ResolvedJavaField field) {
        if (isConstantAnnotationType(field.getDeclaringClass())) {
            throw new UnsupportedFeatureException("Field of annotation proxy is not accessible: " + field);
        }
        return field;
    }

    @Override
    public ResolvedJavaMethod lookup(ResolvedJavaMethod method) {
        if (isConstantAnnotationType(method.getDeclaringClass())) {
            AnnotationSubstitutionType declaringClass = getSubstitution(method.getDeclaringClass());
            AnnotationSubstitutionMethod result = declaringClass.getSubstitutionMethod(method);
            assert result != null && result.original.equals(method);
            return result;
        }
        return method;
    }

    @Override
    public ResolvedJavaMethod resolve(ResolvedJavaMethod method) {
        if (method instanceof AnnotationSubstitutionMethod) {
            return ((AnnotationSubstitutionMethod) method).original;
        }
        return method;
    }

    private synchronized AnnotationSubstitutionType getSubstitution(ResolvedJavaType type) {
        AnnotationSubstitutionType result = getSubstitutionType(type);
        if (result == null) {
            result = new AnnotationSubstitutionType(metaAccess, type);

            for (ResolvedJavaMethod originalMethod : type.getDeclaredMethods()) {
                AnnotationSubstitutionMethod substitutionMethod;
                String methodName = canonicalMethodName(originalMethod);
                if (methodName.equals("equals")) {
                    substitutionMethod = new AnnotationEqualsMethod(originalMethod);
                } else if (methodName.equals("hashCode")) {
                    substitutionMethod = new AnnotationHashCodeMethod(originalMethod);
                } else if (methodName.equals("toString")) {
                    substitutionMethod = new AnnotationToStringMethod(originalMethod);
                } else if (methodName.equals("annotationType")) {
                    substitutionMethod = new AnnotationAnnotationTypeMethod(originalMethod);
                } else {
                    substitutionMethod = new AnnotationAccessorMethod(originalMethod);
                    result.addSubstitutionField(new AnnotationSubstitutionField(result, originalMethod, snippetReflection, metaAccess));
                }
                result.addSubstitutionMethod(originalMethod, substitutionMethod);
            }

            for (ResolvedJavaMethod originalMethod : type.getDeclaredConstructors()) {
                AnnotationSubstitutionMethod substitutionMethod = new AnnotationConstructorMethod(originalMethod);
                result.addSubstitutionMethod(originalMethod, substitutionMethod);
            }

            typeSubstitutions.put(type, result);
        }
        return result;
    }

    static class AnnotationConstructorMethod extends AnnotationSubstitutionMethod {
        AnnotationConstructorMethod(ResolvedJavaMethod original) {
            super(original);
        }

        @Override
        public StructuredGraph buildGraph(DebugContext debug, ResolvedJavaMethod method, HostedProviders providers, Purpose purpose) {
            HostedGraphKit kit = new HostedGraphKit(debug, providers, method);
            StructuredGraph graph = kit.getGraph();
            graph.addAfterFixed(graph.start(), graph.add(new FixedGuardNode(LogicConstantNode.forBoolean(true, graph), DeoptimizationReason.UnreachedCode, DeoptimizationAction.None, true)));
            assert graph.verify();
            return graph;
        }
    }

    /* Used to check the type of fields that need special guarding against missing types. */
    static boolean isClassType(JavaType type, MetaAccessProvider metaAccess) {
        return type.getJavaKind() == JavaKind.Object &&
                        (type.equals(metaAccess.lookupJavaType(Class.class)) || type.equals(metaAccess.lookupJavaType(Class[].class)));
    }

    static class AnnotationAccessorMethod extends AnnotationSubstitutionMethod {
        AnnotationAccessorMethod(ResolvedJavaMethod original) {
            super(original);
        }

        @Override
        public StructuredGraph buildGraph(DebugContext debug, ResolvedJavaMethod method, HostedProviders providers, Purpose purpose) {
            ResolvedJavaType annotationType = method.getDeclaringClass();
            assert !Modifier.isStatic(method.getModifiers()) && method.getSignature().getParameterCount(false) == 0;

            HostedGraphKit kit = new HostedGraphKit(debug, providers, method);
            StructuredGraph graph = kit.getGraph();
            FrameStateBuilder state = new FrameStateBuilder(null, method, graph);
            state.initializeForMethodStart(null, true, providers.getGraphBuilderPlugins());

            /*
             * A random, but unique and consistent, number for every invoke. This is necessary
             * because we, e.g., look up static analysis results by bci.
             */
            int bci = 0;
            graph.start().setStateAfter(state.create(bci++, graph.start()));

            ValueNode receiver = state.loadLocal(0, JavaKind.Object);
            ResolvedJavaField field = findField(annotationType, canonicalMethodName(method));

            ValueNode loadField = kit.append(LoadFieldNode.create(null, receiver, field));

            ResolvedJavaType resultType = method.getSignature().getReturnType(null).resolve(null);

            if (isClassType(resultType, providers.getMetaAccess())) {
                /* Accessor of an annotation element that has a Class type. */

                /* Check if it stores a TypeNotPresentExceptionProxy. */
                ResolvedJavaType exceptionProxyType = providers.getMetaAccess().lookupJavaType(TypeNotPresentExceptionProxy.class);
                TypeReference exceptionProxyTypeRef = TypeReference.createTrusted(kit.getAssumptions(), exceptionProxyType);

                LogicNode condition = kit.append(InstanceOfNode.create(exceptionProxyTypeRef, loadField));
                kit.startIf(condition, BranchProbabilityNode.SLOW_PATH_PROBABILITY);
                kit.thenPart();

                /* Generate the TypeNotPresentException exception and throw it. */
                PiNode casted = kit.createPiNode(loadField, StampFactory.object(exceptionProxyTypeRef, true));
                ResolvedJavaMethod generateExceptionMethod = kit.findMethod(TypeNotPresentExceptionProxy.class, "generateException", false);
                ValueNode exception = kit.createJavaCallWithExceptionAndUnwind(InvokeKind.Virtual, generateExceptionMethod, casted);
                kit.append(new UnwindNode(exception));
                kit.mergeUnwinds();

                kit.elsePart();

                /* Cast the value to the original type. */
                TypeReference resultTypeRef = TypeReference.createTrusted(kit.getAssumptions(), resultType);
                loadField = kit.createPiNode(loadField, StampFactory.object(resultTypeRef, true));

                kit.endIf();
            }

            if (resultType.isArray()) {
                /* From the specification: Arrays with length > 0 need to be cloned. */
                ValueNode arrayLength = kit.append(new ArrayLengthNode(loadField));
                kit.startIf(graph.unique(new IntegerEqualsNode(arrayLength, ConstantNode.forInt(0, graph))), BranchProbabilityNode.NOT_LIKELY_PROBABILITY);
                kit.elsePart();

                ResolvedJavaMethod cloneMethod = kit.findMethod(Object.class, "clone", false);
                JavaType returnType = cloneMethod.getSignature().getReturnType(null);
                StampPair returnStampPair = StampFactory.forDeclaredType(null, returnType, false);

                BasicObjectCloneNode cloned = kit.append(SubstrateGraphBuilderPlugins.objectCloneNode(InvokeKind.Virtual, bci++, returnStampPair, cloneMethod, loadField));
                state.push(returnType.getJavaKind(), cloned);
                cloned.setStateAfter(state.create(bci, cloned));
                state.pop(returnType.getJavaKind());

                ValueNode casted = kit.unique(new PiNode(cloned, resultType, false, false));
                kit.append(new ReturnNode(casted));
                kit.endIf();
            }
            kit.append(new ReturnNode(loadField));

            assert graph.verify();
            return graph;
        }
    }

    static class AnnotationAnnotationTypeMethod extends AnnotationSubstitutionMethod {
        AnnotationAnnotationTypeMethod(ResolvedJavaMethod original) {
            super(original);
        }

        @Override
        public StructuredGraph buildGraph(DebugContext debug, ResolvedJavaMethod method, HostedProviders providers, Purpose purpose) {
            ResolvedJavaType annotationType = method.getDeclaringClass();
            ResolvedJavaType annotationInterfaceType = findAnnotationInterfaceType(annotationType);
            JavaConstant returnValue = providers.getConstantReflection().asJavaClass(annotationInterfaceType);

            GraphKit kit = new HostedGraphKit(debug, providers, method);
            StructuredGraph graph = kit.getGraph();
            ValueNode returnConstant = kit.unique(ConstantNode.forConstant(returnValue, providers.getMetaAccess()));
            kit.append(new ReturnNode(returnConstant));
            return graph;
        }
    }

    static class AnnotationEqualsMethod extends AnnotationSubstitutionMethod {
        AnnotationEqualsMethod(ResolvedJavaMethod original) {
            super(original);
        }

        @Override
        public StructuredGraph buildGraph(DebugContext debug, ResolvedJavaMethod method, HostedProviders providers, Purpose purpose) {
            assert !Modifier.isStatic(method.getModifiers()) && method.getSignature().getParameterCount(false) == 1;
            ResolvedJavaType annotationType = method.getDeclaringClass();
            ResolvedJavaType annotationInterfaceType = findAnnotationInterfaceType(annotationType);

            GraphKit kit = new HostedGraphKit(debug, providers, method);
            StructuredGraph graph = kit.getGraph();
            FrameStateBuilder state = new FrameStateBuilder(null, method, graph);
            state.initializeForMethodStart(null, true, providers.getGraphBuilderPlugins());

            /*
             * A random, but unique and consistent, number for every invoke. This is necessary
             * because we, e.g., look up static analysis results by bci.
             */
            int bci = 0;
            graph.start().setStateAfter(state.create(bci++, graph.start()));

            ValueNode receiver = state.loadLocal(0, JavaKind.Object);
            ValueNode other = state.loadLocal(1, JavaKind.Object);
            ValueNode trueValue = ConstantNode.forBoolean(true, graph);
            ValueNode falseValue = ConstantNode.forBoolean(false, graph);

            kit.startIf(graph.unique(new ObjectEqualsNode(receiver, other)), BranchProbabilityNode.LIKELY_PROBABILITY);
            kit.thenPart();
            kit.append(new ReturnNode(trueValue));
            kit.endIf();

            kit.startIf(graph.unique(InstanceOfNode.create(TypeReference.createTrustedWithoutAssumptions(annotationInterfaceType), other)), BranchProbabilityNode.NOT_LIKELY_PROBABILITY);
            kit.elsePart();
            kit.append(new ReturnNode(falseValue));
            kit.endIf();

            for (String attribute : findAttributes(annotationType)) {
                ResolvedJavaField ourField = findField(annotationType, attribute);
                ResolvedJavaMethod otherMethod = findMethod(annotationInterfaceType, attribute);
                ResolvedJavaType attributeType = ourField.getType().resolve(null);
                // assert attributeType.equals(otherMethod.getSignature().getReturnType(null));

                /*
                 * Access other value. The other object can be any implementation of the annotation
                 * interface, so we need to invoke the accessor method.
                 */
                ValueNode otherAttribute = kit.createInvokeWithExceptionAndUnwind(otherMethod, InvokeKind.Interface, state, bci++, bci++, other);

                /* Access our value. We know that it is in a field. */
                ValueNode ourAttribute = kit.append(LoadFieldNode.create(null, receiver, ourField));

                if (attributeType.isPrimitive()) {
                    /*
                     * Box primitive types. The equality on attributes is defined on boxed values
                     * (which matters, e.g., for floating point values), and it is easier to call
                     * equals() on the boxed type anyway (and rely on method inlining and boxing
                     * elimination to clean things up).
                     */
                    ResolvedJavaType boxedAttributeType = providers.getMetaAccess().lookupJavaType(attributeType.getJavaKind().toBoxedJavaClass());
                    ourAttribute = kit.append(new BoxNode(ourAttribute, boxedAttributeType, attributeType.getJavaKind()));
                    otherAttribute = kit.append(new BoxNode(otherAttribute, boxedAttributeType, attributeType.getJavaKind()));
                }

                ValueNode attributeEqual;
                if (attributeType.isArray()) {
                    /* Call the appropriate Arrays.equals() method for our attribute type. */
                    ResolvedJavaMethod m = findMethod(providers.getMetaAccess().lookupJavaType(Arrays.class), "equals", attributeType, attributeType);
                    attributeEqual = kit.createInvokeWithExceptionAndUnwind(m, InvokeKind.Static, state, bci++, bci++, ourAttribute, otherAttribute);
                } else {
                    /* Just call Object.equals(). Primitive values are already boxed. */
                    ResolvedJavaMethod m = kit.findMethod(Object.class, "equals", false);
                    attributeEqual = kit.createInvokeWithExceptionAndUnwind(m, InvokeKind.Virtual, state, bci++, bci++, ourAttribute, otherAttribute);
                }

                kit.startIf(graph.unique(new IntegerEqualsNode(attributeEqual, trueValue)), BranchProbabilityNode.LIKELY_PROBABILITY);
                kit.elsePart();
                kit.append(new ReturnNode(falseValue));
                kit.endIf();
            }
            kit.append(new ReturnNode(trueValue));

            assert graph.verify();
            return graph;
        }

    }

    static class AnnotationHashCodeMethod extends AnnotationSubstitutionMethod {
        AnnotationHashCodeMethod(ResolvedJavaMethod original) {
            super(original);
        }

        @Override
        public StructuredGraph buildGraph(DebugContext debug, ResolvedJavaMethod method, HostedProviders providers, Purpose purpose) {
            assert !Modifier.isStatic(method.getModifiers()) && method.getSignature().getParameterCount(false) == 0;
            ResolvedJavaType annotationType = method.getDeclaringClass();

            GraphKit kit = new HostedGraphKit(debug, providers, method);
            StructuredGraph graph = kit.getGraph();
            FrameStateBuilder state = new FrameStateBuilder(null, method, graph);
            state.initializeForMethodStart(null, true, providers.getGraphBuilderPlugins());

            /*
             * A random, but unique and consistent, number for every invoke. This is necessary
             * because we, e.g., look up static analysis results by bci.
             */
            int bci = 0;
            graph.start().setStateAfter(state.create(bci++, graph.start()));

            ValueNode receiver = state.loadLocal(0, JavaKind.Object);
            ValueNode result = ConstantNode.forInt(0, graph);

            for (String attribute : findAttributes(annotationType)) {
                ResolvedJavaField ourField = findField(annotationType, attribute);
                ResolvedJavaType attributeType = ourField.getType().resolve(null);

                /* Access our value. We know that it is in a field. */
                ValueNode ourAttribute = kit.append(LoadFieldNode.create(null, receiver, ourField));

                if (attributeType.isPrimitive()) {
                    /* Box primitive types. */
                    ResolvedJavaType boxedAttributeType = providers.getMetaAccess().lookupJavaType(attributeType.getJavaKind().toBoxedJavaClass());
                    ourAttribute = kit.append(new BoxNode(ourAttribute, boxedAttributeType, attributeType.getJavaKind()));
                }

                ValueNode attributeHashCode;
                if (attributeType.isArray()) {
                    /* Call the appropriate Arrays.hashCode() method for our attribute type. */
                    ResolvedJavaMethod m = findMethod(providers.getMetaAccess().lookupJavaType(Arrays.class), "hashCode", attributeType);
                    attributeHashCode = kit.createInvokeWithExceptionAndUnwind(m, InvokeKind.Static, state, bci++, bci++, ourAttribute);
                } else {
                    /* Just call Object.hashCode(). Primitive values are already boxed. */
                    ResolvedJavaMethod m = kit.findMethod(Object.class, "hashCode", false);
                    attributeHashCode = kit.createInvokeWithExceptionAndUnwind(m, InvokeKind.Virtual, state, bci++, bci++, ourAttribute);
                }

                /* From the specification: sum up "name.hashCode() * 127 ^ value.hashCode()" */
                attributeHashCode = kit.unique(new XorNode(attributeHashCode, ConstantNode.forInt(127 * attribute.hashCode(), graph)));
                result = kit.unique(new AddNode(result, attributeHashCode));
            }
            kit.append(new ReturnNode(result));

            assert graph.verify();
            return graph;
        }
    }

    static class AnnotationToStringMethod extends AnnotationSubstitutionMethod {

        AnnotationToStringMethod(ResolvedJavaMethod original) {
            super(original);
        }

        @Override
        public StructuredGraph buildGraph(DebugContext debug, ResolvedJavaMethod method, HostedProviders providers, Purpose purpose) {
            assert !Modifier.isStatic(method.getModifiers()) && method.getSignature().getParameterCount(false) == 0;
            ResolvedJavaType annotationType = method.getDeclaringClass();
            ResolvedJavaType annotationInterfaceType = findAnnotationInterfaceType(annotationType);

            GraphKit kit = new HostedGraphKit(debug, providers, method);
            StructuredGraph graph = kit.getGraph();

            FrameStateBuilder state = new FrameStateBuilder(null, method, graph);
            state.initializeForMethodStart(null, true, providers.getGraphBuilderPlugins());
            graph.start().setStateAfter(state.create(0, graph.start()));

            String returnValue = "@" + annotationInterfaceType.toJavaName(true);
            ValueNode returnConstant = kit.unique(ConstantNode.forConstant(SubstrateObjectConstant.forObject(returnValue), providers.getMetaAccess()));
            kit.append(new ReturnNode(returnConstant));
            return graph;
        }
    }

    /*
     * This method retrieves the annotation interface type from an annotation proxy type represented
     * as an AnnotationSubstitutionType (or a type that wraps an AnnotationSubstitutionType). The
     * ConstantAnnotationMarker interface is already filtered out when
     * AnnotationSubstitutionType.getInterfaces() is called.
     */
    private static ResolvedJavaType findAnnotationInterfaceType(ResolvedJavaType annotationType) {
        VMError.guarantee(Inflation.toWrappedType(annotationType) instanceof AnnotationSubstitutionType);
        ResolvedJavaType[] interfaces = annotationType.getInterfaces();
        VMError.guarantee(interfaces.length == 1, "Unexpected number of interfaces for annotation proxy class.");
        return interfaces[0];
    }

    /**
     * This method retrieves the annotation interface type from a marked annotation proxy type.
     * Annotation proxy types implement only the annotation interface by default. However, since we
     * inject the ConstantAnnotationMarker the Annotation proxy types for ahead-of-time allocated
     * annotations implement two interfaces. We make sure we return the right one here.
     */
    static ResolvedJavaType findAnnotationInterfaceTypeForMarkedAnnotationType(ResolvedJavaType annotationType, MetaAccessProvider metaAccess) {
        ResolvedJavaType[] interfaces = annotationType.getInterfaces();
        VMError.guarantee(interfaces.length == 2, "Unexpected number of interfaces for annotation proxy class.");
        VMError.guarantee(interfaces[1].equals(metaAccess.lookupJavaType(ConstantAnnotationMarker.class)));
        return interfaces[0];
    }

    /*
     * This method is similar to the above one, with the difference that it takes a Class<?> instead
     * of an ResolvedJavaType as an argument.
     */
    static Class<?> findAnnotationInterfaceTypeForMarkedAnnotationType(Class<? extends Proxy> clazz) {
        Class<?>[] interfaces = clazz.getInterfaces();
        VMError.guarantee(interfaces.length == 2, "Unexpected number of interfaces for annotation proxy class.");
        VMError.guarantee(interfaces[1].equals(ConstantAnnotationMarker.class));
        return interfaces[0];
    }
}

@TargetClass(className = "sun.reflect.annotation.AnnotationType")
final class Target_sun_reflect_annotation_AnnotationType {

    /**
     * In JDK this class lazily initializes AnnotationTypes as they are requested.
     *
     * In SVM we analyze only the types that are used as {@link java.lang.annotation.Repeatable}
     * annotations and pre-initialize those.
     *
     * If this method fails, introduce missing pre-initialization rules in
     * {@link AnnotationTypeFeature}.
     */
    @Substitute
    public static AnnotationType getInstance(Class<? extends Annotation> annotationClass) {
        return ImageSingletons.lookup(AnnotationTypeSupport.class).getInstance(annotationClass);
    }

}

@AutomaticFeature
class AnnotationSupportFeature implements Feature {

    @Override
    public void duringSetup(DuringSetupAccess access) {
        DuringSetupAccessImpl config = (DuringSetupAccessImpl) access;
        access.registerObjectReplacer(new AnnotationObjectReplacer(config.getImageClassLoader().getClassLoader()));
    }
}

/**
 * This replacer replaces the annotation proxy instances with a clone that additionaly implements
 * the ConstantAnnotationMarker interface.
 */
class AnnotationObjectReplacer implements Function<Object, Object> {

    private final ClassLoader classLoader;
    /**
     * Cache the replaced objects to ensure that they are only replaced once. We are using a
     * concurrent hash map because replace() may be called from BigBang.finish(), which is
     * multi-threaded.
     * 
     * A side effect of this caching is de-duplication of annotation instances. When running as a
     * native image two equal annotation instances are also identical. On HotSpot that is not true,
     * the two annotation instances, although equal, are actually two distinct objects. Although
     * this is a small deviation from HotSpot semantics it can improve the native image size.
     * 
     * If de-duplication is not desired that can be achieved by replacing the ConcurrentHashMap with
     * an IdentityHashMap (and additional access synchronisation).
     */
    private ConcurrentHashMap<Object, Object> objectCache = new ConcurrentHashMap<>();

    AnnotationObjectReplacer(ClassLoader loader) {
        this.classLoader = loader;
    }

    @Override
    public Object apply(Object original) {
        Class<?> clazz = original.getClass();
        if (Annotation.class.isAssignableFrom(clazz) && Proxy.class.isAssignableFrom(clazz)) {
            return objectCache.computeIfAbsent(original, obj -> replacementComputer(obj, classLoader));
        }

        return original;
    }

    /**
     * Effectively clones the original proxy object and it adds the ConstantAnnotationMarker
     * interface.
     */
    private static Object replacementComputer(Object original, ClassLoader classLoader) {
        Class<?>[] interfaces = original.getClass().getInterfaces();
        Class<?>[] extendedInterfaces = Arrays.copyOf(interfaces, interfaces.length + 1);
        extendedInterfaces[extendedInterfaces.length - 1] = ConstantAnnotationMarker.class;

        return Proxy.newProxyInstance(classLoader, extendedInterfaces, Proxy.getInvocationHandler(original));
    }

}
