/*
 * Copyright 2023 Salesforce, Inc. All rights reserved.
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
/**
 * Bootstrap API for the Mule Container.
 *
 * @moduleGraph
 * @since 4.6
 */
module org.mule.boot.api {

  exports org.mule.runtime.module.reboot.api;

  // TODO W-13151134: export only to modules that require it once org.mule.runtime.launcher is modularized
  // exports org.mule.runtime.module.reboot.internal to org.mule.boot.tanuki,org.mule.runtime.launcher,com.mulesoft.mule.runtime.plugin;
  exports org.mule.runtime.module.boot.internal;

  // Needed by the MuleLog4jConfigurer
  requires org.mule.runtime.boot.log4j;

  // Needed by the BootModuleLayerValidationBootstrapConfigurer and for creating the container ClassLoader
  requires org.mule.runtime.jpms.utils;

  // Needed by the SLF4JBridgeHandlerBootstrapConfigurer, but also to make the logging modules available from the boot layer
  requires org.mule.runtime.logging;

  requires commons.cli;
}