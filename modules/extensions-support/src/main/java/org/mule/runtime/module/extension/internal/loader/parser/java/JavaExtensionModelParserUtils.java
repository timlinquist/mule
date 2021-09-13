/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.module.extension.internal.loader.parser.java;

import static java.lang.String.format;
import static java.util.Arrays.stream;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static java.util.Optional.empty;
import static java.util.stream.Collectors.toList;
import static org.mule.runtime.api.meta.ExpressionSupport.NOT_SUPPORTED;
import static org.mule.runtime.core.api.util.StringUtils.ifNotBlank;
import static org.mule.runtime.core.api.util.StringUtils.isBlank;

import org.mule.runtime.api.meta.ExpressionSupport;
import org.mule.runtime.api.meta.model.ExternalLibraryModel;
import org.mule.runtime.api.meta.model.deprecated.DeprecationModel;
import org.mule.runtime.extension.api.annotation.ExternalLib;
import org.mule.runtime.extension.api.annotation.ExternalLibs;
import org.mule.runtime.extension.api.annotation.deprecated.Deprecated;
import org.mule.runtime.extension.api.annotation.license.RequiresEnterpriseLicense;
import org.mule.runtime.extension.api.annotation.license.RequiresEntitlement;
import org.mule.runtime.extension.api.annotation.metadata.MetadataKeyId;
import org.mule.runtime.extension.api.annotation.param.Config;
import org.mule.runtime.extension.api.annotation.param.Connection;
import org.mule.runtime.extension.api.annotation.param.Parameter;
import org.mule.runtime.extension.api.annotation.param.ParameterGroup;
import org.mule.runtime.extension.api.exception.IllegalModelDefinitionException;
import org.mule.runtime.extension.api.exception.IllegalParameterModelDefinitionException;
import org.mule.runtime.extension.api.loader.ExtensionLoadingContext;
import org.mule.runtime.extension.api.model.deprecated.ImmutableDeprecationModel;
import org.mule.runtime.extension.api.runtime.process.CompletionCallback;
import org.mule.runtime.extension.api.runtime.route.Chain;
import org.mule.runtime.extension.api.runtime.streaming.PagingProvider;
import org.mule.runtime.module.extension.api.loader.ModelLoaderDelegate;
import org.mule.runtime.module.extension.api.loader.java.type.ComponentElement;
import org.mule.runtime.module.extension.api.loader.java.type.ConnectionProviderElement;
import org.mule.runtime.module.extension.api.loader.java.type.ExtensionElement;
import org.mule.runtime.module.extension.api.loader.java.type.ExtensionParameter;
import org.mule.runtime.module.extension.api.loader.java.type.FunctionContainerElement;
import org.mule.runtime.module.extension.api.loader.java.type.FunctionElement;
import org.mule.runtime.module.extension.api.loader.java.type.MethodElement;
import org.mule.runtime.module.extension.api.loader.java.type.OperationElement;
import org.mule.runtime.module.extension.api.loader.java.type.SourceElement;
import org.mule.runtime.module.extension.api.loader.java.type.Type;
import org.mule.runtime.module.extension.api.loader.java.type.WithAnnotations;
import org.mule.runtime.module.extension.api.loader.java.type.WithOperationContainers;
import org.mule.runtime.module.extension.api.loader.java.type.WithParameters;
import org.mule.runtime.module.extension.internal.loader.parser.ConnectionProviderModelParser;
import org.mule.runtime.module.extension.internal.loader.parser.FunctionModelParser;
import org.mule.runtime.module.extension.internal.loader.parser.OperationModelParser;
import org.mule.runtime.module.extension.internal.loader.parser.ParameterGroupModelParser;
import org.mule.runtime.module.extension.internal.loader.parser.ParameterModelParser;
import org.mule.runtime.module.extension.internal.loader.parser.ParameterModelParserDecorator;
import org.mule.runtime.module.extension.internal.loader.parser.SourceModelParser;
import org.mule.runtime.module.extension.internal.loader.parser.java.info.RequiresEnterpriseLicenseInfo;
import org.mule.runtime.module.extension.internal.loader.parser.java.info.RequiresEntitlementInfo;

import java.lang.annotation.Annotation;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Utility class for {@link ModelLoaderDelegate model loaders}
 *
 * @since 1.0
 */
public final class JavaExtensionModelParserUtils {

  private JavaExtensionModelParserUtils() {}

  public static List<ExtensionParameter> getCompletionCallbackParameters(MethodElement method) {
    return method.getParameters().stream()
        .filter(p -> {
          Type type = p.getType();
          return type.isAssignableTo(CompletionCallback.class) ||
              type.isAssignableTo(org.mule.sdk.api.runtime.process.CompletionCallback.class);
        })
        .collect(toList());
  }

  public static boolean isCompletionCallbackParameter(ExtensionParameter extensionParameter) {
    return extensionParameter.getType().isAssignableTo(CompletionCallback.class) ||
        extensionParameter.getType().isAssignableTo(org.mule.sdk.api.runtime.process.CompletionCallback.class);
  }

  public static boolean isAutoPaging(MethodElement operationMethod) {
    Type returnType = operationMethod.getReturnType();
    return returnType.isAssignableTo(PagingProvider.class)
        || returnType.isAssignableTo(org.mule.sdk.api.runtime.streaming.PagingProvider.class);
  }

  public static boolean isProcessorChain(ExtensionParameter parameter) {
    Type type = parameter.getType();
    return type.isAssignableTo(Chain.class)
        || type.isAssignableTo(org.mule.sdk.api.runtime.route.Chain.class);
  }

  public static boolean isParameterGroup(ExtensionParameter groupParameter) {
    return groupParameter.getAnnotation(ParameterGroup.class).isPresent()
        || groupParameter.getAnnotation(org.mule.sdk.api.annotation.param.ParameterGroup.class).isPresent();
  }

  public static boolean isParameter(ExtensionParameter parameter) {
    return parameter.getAnnotation(Parameter.class).isPresent()
        || parameter.getAnnotation(org.mule.sdk.api.annotation.param.Parameter.class).isPresent();
  }

  public static List<ExternalLibraryModel> parseExternalLibraryModels(WithAnnotations element) {
    Optional<ExternalLibs> externalLibs = element.getAnnotation(ExternalLibs.class);
    if (externalLibs.isPresent()) {
      return stream(externalLibs.get().value())
          .map(lib -> parseExternalLib(lib))
          .collect(toList());
    } else {
      return element.getAnnotation(ExternalLib.class)
          .map(lib -> singletonList(parseExternalLib(lib)))
          .orElse(emptyList());
    }
  }

  public static List<OperationModelParser> getOperationParsers(ExtensionElement extensionElement,
                                                               WithOperationContainers operationContainers,
                                                               ExtensionLoadingContext loadingContext) {
    return operationContainers.getOperationContainers().stream()
        .flatMap(container -> container.getOperations().stream()
            .map(method -> new JavaOperationModelParser(extensionElement, container, method, loadingContext)))
        .collect(toList());
  }

  public static List<SourceModelParser> getSourceParsers(ExtensionElement extensionElement,
                                                         List<SourceElement> sources,
                                                         ExtensionLoadingContext loadingContext) {
    return sources.stream()
        .map(source -> new JavaSourceModelParser(extensionElement, source, loadingContext))
        .collect(toList());
  }

  public static List<ConnectionProviderModelParser> getConnectionProviderModelParsers(ExtensionElement extensionElement,
                                                                                      List<ConnectionProviderElement> connectionProviderElements) {

    return connectionProviderElements.stream()
        .map(cpElement -> new JavaConnectionProviderModelParser(extensionElement, cpElement))
        .collect(toList());
  }

  public static List<FunctionModelParser> getFunctionModelParsers(ExtensionElement extensionElement,
                                                                  List<FunctionContainerElement> functionContainers,
                                                                  ExtensionLoadingContext loadingContext) {
    return functionContainers.stream()
        .flatMap(container -> container.getFunctions().stream())
        .map(func -> new JavaFunctionModelParser(extensionElement, func, loadingContext))
        .collect(toList());
  }

  public static List<ParameterGroupModelParser> getParameterGroupParsers(List<? extends ExtensionParameter> parameters,
                                                                         ParameterDeclarationContext context) {
    return getParameterGroupParsers(parameters, context, null);
  }

  public static List<ParameterGroupModelParser> getSourceParameterGroupParsers(List<? extends ExtensionParameter> parameters,
                                                                               ParameterDeclarationContext context) {

    return getParameterGroupParsers(parameters, context, p -> new ParameterModelParserDecorator(p) {

      @Override
      public ExpressionSupport getExpressionSupport() {
        return NOT_SUPPORTED;
      }
    });
  }

  static List<ParameterGroupModelParser> getParameterGroupParsers(List<? extends ExtensionParameter> parameters,
                                                                  ParameterDeclarationContext context,
                                                                  Function<ParameterModelParser, ParameterModelParser> parameterMutator) {
    checkAnnotationsNotUsedMoreThanOnce(parameters,
                                        Connection.class,
                                        org.mule.sdk.api.annotation.param.Connection.class,
                                        Config.class,
                                        org.mule.sdk.api.annotation.param.Config.class,
                                        MetadataKeyId.class,
                                        org.mule.sdk.api.annotation.metadata.MetadataKeyId.class);

    List<ParameterGroupModelParser> groups = new LinkedList<>();
    List<ExtensionParameter> defaultGroupParams = new LinkedList<>();
    boolean defaultGroupAdded = false;

    for (ExtensionParameter extensionParameter : parameters) {
      if (!extensionParameter.shouldBeAdvertised()) {
        continue;
      }

      if (isParameterGroup(extensionParameter)) {
        groups.add(new JavaDeclaredParameterGroupModelParser(extensionParameter, context, parameterMutator));
      } else {
        defaultGroupParams.add(extensionParameter);
        if (!defaultGroupAdded) {
          groups.add(new JavaDefaultParameterGroupParser(defaultGroupParams, context, parameterMutator));
          defaultGroupAdded = true;
        }
      }
    }

    return groups;
  }

  private static void checkAnnotationsNotUsedMoreThanOnce(List<? extends ExtensionParameter> parameters,
                                                          Class<? extends Annotation>... annotations) {
    for (Class<? extends Annotation> annotation : annotations) {
      int usages = 0;
      for (ExtensionParameter param : parameters) {
        if (param.isAnnotatedWith(annotation) && ++usages > 1) {
          throw new IllegalModelDefinitionException(format("The defined parameters %s from %s, uses the annotation @%s more than once",
                                                           parameters.stream().map(p -> p.getName()).collect(toList()),
                                                           parameters.iterator().next().getOwnerDescription(),
                                                           annotation.getSimpleName()));
        }
      }
    }
  }

  public static Optional<ExtensionParameter> getConfigParameter(WithParameters element) {
    Optional<ExtensionParameter> configParameter = element.getParametersAnnotatedWith(Config.class).stream().findFirst();
    if (!configParameter.isPresent()) {
      configParameter = element.getParametersAnnotatedWith(org.mule.sdk.api.annotation.param.Config.class).stream().findFirst();
    }

    return configParameter;
  }

  public static Optional<ExtensionParameter> getConnectionParameter(WithParameters element) {
    Optional<ExtensionParameter> connectionParameter = element.getParametersAnnotatedWith(Connection.class).stream().findFirst();
    if (!connectionParameter.isPresent()) {
      connectionParameter =
          element.getParametersAnnotatedWith(org.mule.sdk.api.annotation.param.Connection.class).stream().findFirst();
    }

    return connectionParameter;
  }

  public static Optional<DeprecationModel> getDeprecationModel(ExtensionParameter extensionParameter) {
    return getDeprecationModel(extensionParameter, "Parameter", extensionParameter.getName());
  }

  public static Optional<DeprecationModel> getDeprecationModel(FunctionElement functionElement) {
    return getDeprecationModel(functionElement, "Function", functionElement.getName());
  }

  public static Optional<DeprecationModel> getDeprecationModel(OperationElement operationElement) {
    return getDeprecationModel(operationElement, "Operation", operationElement.getName());
  }

  public static Optional<DeprecationModel> getDeprecationModel(SourceElement sourceElement) {
    return getDeprecationModel(sourceElement, "Source", sourceElement.getName());
  }

  public static Optional<DeprecationModel> getDeprecationModel(ConnectionProviderElement connectionProviderElement) {
    return getDeprecationModel(connectionProviderElement, "Connection provider", connectionProviderElement.getName());
  }

  public static Optional<DeprecationModel> getDeprecationModel(ComponentElement componentElement) {
    return getDeprecationModel(componentElement, "Component", componentElement.getName());
  }

  public static Optional<DeprecationModel> getDeprecationModel(ExtensionElement extensionElement) {
    return getDeprecationModel(extensionElement, "Extension", extensionElement.getName());
  }

  public static Optional<RequiresEnterpriseLicenseInfo> getRequiresEnterpriseLicenseInfo(ExtensionElement extensionElement) {
    return getInfoFromExtension(extensionElement, RequiresEnterpriseLicense.class,
                                org.mule.sdk.api.annotation.license.RequiresEnterpriseLicense.class,
                                requiresEnterpriseLicense -> new RequiresEnterpriseLicenseInfo(requiresEnterpriseLicense
                                    .allowEvaluationLicense()),
                                requiresEnterpriseLicense -> new RequiresEnterpriseLicenseInfo(requiresEnterpriseLicense
                                    .allowEvaluationLicense()));
  }

  public static Optional<RequiresEntitlementInfo> getRequiresEntitlementInfo(ExtensionElement extensionElement) {
    return getInfoFromExtension(extensionElement, RequiresEntitlement.class,
                                org.mule.sdk.api.annotation.license.RequiresEntitlement.class,
                                requiresEntitlementAnnotation -> new RequiresEntitlementInfo(requiresEntitlementAnnotation.name(),
                                                                                             requiresEntitlementAnnotation
                                                                                                 .description()),
                                requiresEntitlementAnnotation -> new RequiresEntitlementInfo(requiresEntitlementAnnotation.name(),
                                                                                             requiresEntitlementAnnotation
                                                                                                 .description()));
  }

  private static <R extends Annotation, S extends Annotation, T> Optional<T> getInfoFromExtension(ExtensionElement extensionElement,
                                                                                                  Class<R> legacyAnnotationClass,
                                                                                                  Class<S> sdkAnnotationClass,
                                                                                                  Function<R, T> legacyAnnotationMapping,
                                                                                                  Function<S, T> sdkAnnotationMapping) {
    Optional<R> legacyAnnotation = extensionElement.getAnnotation(legacyAnnotationClass);
    Optional<S> sdkAnnotation = extensionElement.getAnnotation(sdkAnnotationClass);

    Optional<T> result;
    if (legacyAnnotation.isPresent() && sdkAnnotation.isPresent()) {
      throw new IllegalParameterModelDefinitionException(format("Extension '%s' is annotated with '@%s' and '@%s' at the same time",
                                                                extensionElement.getName(),
                                                                legacyAnnotationClass.getName(),
                                                                sdkAnnotationClass.getName()));
    } else if (legacyAnnotation.isPresent()) {
      result = legacyAnnotation
          .map(legacyAnnotationMapping);
    } else if (sdkAnnotation.isPresent()) {
      result = sdkAnnotation
          .map(sdkAnnotationMapping);
    } else {
      result = empty();
    }

    return result;
  }

  private static Optional<DeprecationModel> getDeprecationModel(WithAnnotations element, String elementType, String elementName) {
    Optional<Deprecated> legacyAnnotation = element.getAnnotation(Deprecated.class);
    Optional<org.mule.sdk.api.annotation.deprecated.Deprecated> sdkAnnotation =
        element.getAnnotation(org.mule.sdk.api.annotation.deprecated.Deprecated.class);

    Optional<DeprecationModel> deprecationModel;
    if (legacyAnnotation.isPresent() && sdkAnnotation.isPresent()) {
      throw new IllegalParameterModelDefinitionException(format("%s '%s' is annotated with '@%s' and '@%s' at the same time",
                                                                elementType,
                                                                elementName,
                                                                Deprecated.class.getName(),
                                                                org.mule.sdk.api.annotation.deprecated.Deprecated.class
                                                                    .getName()));
    } else if (legacyAnnotation.isPresent()) {
      deprecationModel = legacyAnnotation
          .map(deprecated -> {
            String toRemoveIn = isBlank(deprecated.toRemoveIn()) ? null : deprecated.toRemoveIn();
            return new ImmutableDeprecationModel(deprecated.message(), deprecated.since(), toRemoveIn);
          });
    } else if (sdkAnnotation.isPresent()) {
      deprecationModel = sdkAnnotation
          .map(deprecated -> {
            String toRemoveIn = isBlank(deprecated.toRemoveIn()) ? null : deprecated.toRemoveIn();
            return new ImmutableDeprecationModel(deprecated.message(), deprecated.since(), toRemoveIn);
          });
    } else {
      deprecationModel = empty();
    }

    return deprecationModel;
  }

  private static ExternalLibraryModel parseExternalLib(ExternalLib externalLibAnnotation) {
    ExternalLibraryModel.ExternalLibraryModelBuilder builder = ExternalLibraryModel.builder()
        .withName(externalLibAnnotation.name())
        .withDescription(externalLibAnnotation.description())
        .withType(externalLibAnnotation.type())
        .isOptional(externalLibAnnotation.optional());

    ifNotBlank(externalLibAnnotation.nameRegexpMatcher(), builder::withRegexpMatcher);
    ifNotBlank(externalLibAnnotation.requiredClassName(), builder::withRequiredClassName);
    ifNotBlank(externalLibAnnotation.coordinates(), builder::withCoordinates);

    return builder.build();
  }
}
