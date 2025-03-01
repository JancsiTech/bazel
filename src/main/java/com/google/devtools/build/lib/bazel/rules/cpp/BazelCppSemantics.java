// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.bazel.rules.cpp;

import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.RuleErrorConsumer;
import com.google.devtools.build.lib.analysis.TransitiveInfoCollection;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.packages.AspectDescriptor;
import com.google.devtools.build.lib.packages.Provider;
import com.google.devtools.build.lib.packages.StarlarkProvider;
import com.google.devtools.build.lib.packages.StructImpl;
import com.google.devtools.build.lib.rules.cpp.AspectLegalCppSemantics;
import com.google.devtools.build.lib.rules.cpp.CcCommon.Language;
import com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.FeatureConfiguration;
import com.google.devtools.build.lib.rules.cpp.CcToolchainProvider;
import com.google.devtools.build.lib.rules.cpp.CppActionNames;
import com.google.devtools.build.lib.rules.cpp.CppCompileActionBuilder;
import com.google.devtools.build.lib.rules.cpp.CppConfiguration;
import com.google.devtools.build.lib.rules.cpp.CppConfiguration.HeadersCheckingMode;
import com.google.devtools.build.lib.rules.cpp.CppFileTypes;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.SerializationConstant;
import javax.annotation.Nullable;

/** C++ compilation semantics. */
public class BazelCppSemantics implements AspectLegalCppSemantics {
  @SerializationConstant
  public static final BazelCppSemantics CPP = new BazelCppSemantics(Language.CPP);

  @SerializationConstant
  public static final BazelCppSemantics OBJC = new BazelCppSemantics(Language.OBJC);

  // TODO(#10338): We need to check for both providers. With and without the @rules_cc repo name.
  //  The reason for that is that when we are in a target inside @rules_cc, the provider won't have
  // the repo name set.
  public static final Provider.Key CC_SHARED_INFO_PROVIDER_RULES_CC =
      new StarlarkProvider.Key(
          Label.parseAbsoluteUnchecked("@rules_cc//examples:experimental_cc_shared_library.bzl"),
          "CcSharedLibraryInfo");

  public static final Provider.Key CC_SHARED_INFO_PROVIDER =
      new StarlarkProvider.Key(
          Label.parseAbsoluteUnchecked("//examples:experimental_cc_shared_library.bzl"),
          "CcSharedLibraryInfo");

  public static final Provider.Key CC_SHARED_INFO_PROVIDER_BUILT_INS =
      new StarlarkProvider.Key(
          Label.parseAbsoluteUnchecked("@_builtins//:common/cc/experimental_cc_shared_library.bzl"),
          "CcSharedLibraryInfo");

  private final Language language;

  private BazelCppSemantics(Language language) {
    this.language = language;
  }

  @Override
  public Language language() {
    return language;
  }

  @Override
  public void finalizeCompileActionBuilder(
      BuildConfigurationValue configuration,
      FeatureConfiguration featureConfiguration,
      CppCompileActionBuilder actionBuilder,
      RuleErrorConsumer ruleErrorConsumer) {
    CcToolchainProvider toolchain = actionBuilder.getToolchain();
    if (language == Language.CPP) {
      CppConfiguration cppConfig = configuration.getFragment(CppConfiguration.class);
      Artifact sourceFile = actionBuilder.getSourceFile();
      actionBuilder
          .addTransitiveMandatoryInputs(
              cppConfig.useSpecificToolFiles() && !actionBuilder.getSourceFile().isTreeArtifact()
                  ? (actionBuilder.getActionName().equals(CppActionNames.ASSEMBLE)
                      ? toolchain.getAsFiles()
                      : toolchain.getCompilerFiles())
                  : toolchain.getAllFiles())
          .setShouldScanIncludes(
              cppConfig.experimentalIncludeScanning()
                  && featureConfiguration.getRequestedFeatures().contains("cc_include_scanning")
                  && !sourceFile.isFileType(CppFileTypes.ASSEMBLER)
                  && !sourceFile.isFileType(CppFileTypes.CPP_MODULE));
    } else {
      actionBuilder
          .addTransitiveMandatoryInputs(toolchain.getAllFilesIncludingLibc())
          .setShouldScanIncludes(false);
    }
  }

  @Override
  public HeadersCheckingMode determineHeadersCheckingMode(RuleContext ruleContext) {
    return HeadersCheckingMode.STRICT;
  }

  @Override
  public HeadersCheckingMode determineStarlarkHeadersCheckingMode(
      RuleContext ruleContext, CppConfiguration cppConfig, CcToolchainProvider toolchain) {
    if (cppConfig.strictHeaderCheckingFromStarlark()) {
      return HeadersCheckingMode.STRICT;
    }
    return HeadersCheckingMode.LOOSE;
  }

  @Override
  public boolean allowIncludeScanning() {
    return true;
  }

  @Override
  public boolean needsDotdInputPruning(BuildConfigurationValue configuration) {
    if (language == Language.CPP) {
      return true;
    } else {
      return configuration.getFragment(CppConfiguration.class).objcShouldGenerateDotdFiles();
    }
  }

  @Override
  public void validateAttributes(RuleContext ruleContext) {
  }

  @Override
  public boolean needsIncludeValidation() {
    return language != Language.OBJC;
  }

  @Override
  @Nullable
  public StructImpl getCcSharedLibraryInfo(TransitiveInfoCollection dep) {
    StructImpl ccSharedLibraryInfo = (StructImpl) dep.get(CC_SHARED_INFO_PROVIDER);
    if (ccSharedLibraryInfo != null) {
      return ccSharedLibraryInfo;
    }
    ccSharedLibraryInfo = (StructImpl) dep.get(CC_SHARED_INFO_PROVIDER_RULES_CC);
    if (ccSharedLibraryInfo != null) {
      return ccSharedLibraryInfo;
    }
    ccSharedLibraryInfo = (StructImpl) dep.get(CC_SHARED_INFO_PROVIDER_BUILT_INS);
    if (ccSharedLibraryInfo != null) {
      return ccSharedLibraryInfo;
    }
    return null;
  }

  @Override
  public void validateLayeringCheckFeatures(
      RuleContext ruleContext,
      AspectDescriptor aspectDescriptor,
      CcToolchainProvider ccToolchain,
      ImmutableSet<String> unsupportedFeatures) {}

  @Override
  public boolean createEmptyArchive() {
    return false;
  }

  @Override
  public boolean shouldUseInterfaceDepsBehavior(RuleContext ruleContext) {
    boolean experimentalCcInterfaceDeps =
        ruleContext.getFragment(CppConfiguration.class).experimentalCcInterfaceDeps();
    if (!experimentalCcInterfaceDeps
        && ruleContext.attributes().isAttributeValueExplicitlySpecified("interface_deps")) {
      ruleContext.attributeError("interface_deps", "requires --experimental_cc_interface_deps");
    }
    return experimentalCcInterfaceDeps;
  }
}
