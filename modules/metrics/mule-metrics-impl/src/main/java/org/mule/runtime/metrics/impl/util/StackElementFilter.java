/*
 * Copyright 2023 Salesforce, Inc. All rights reserved.
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.metrics.impl.util;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Component in charge of accepting or rejecting {@link StackTraceElement elements} when computing a stack trace hash
 */
public abstract class StackElementFilter {

  /**
   * Tests whether the specified {@link StackTraceElement} should be accepted when computing a stack hash.
   *
   * @param element The {@link StackTraceElement} to be tested
   * @return {@code true} if and only if {@code element} should be accepted
   */
  public abstract boolean accept(StackTraceElement element);

  /**
   * Creates a {@link StackElementFilter} that accepts any stack trace elements
   *
   * @return the filter
   */
  public static StackElementFilter any() {
    return new StackElementFilter() {

      @Override
      public boolean accept(StackTraceElement element) {
        return true;
      }
    };
  }

  /**
   * Creates a {@link StackElementFilter} that accepts all stack trace elements with a non {@code null} {@code {@link
   * StackTraceElement#getFileName()} filename} and positive {@link StackTraceElement#getLineNumber()} line number
   *
   * @return the filter
   */
  public static StackElementFilter withSourceInfo() {
    return new StackElementFilter() {

      @Override
      public boolean accept(StackTraceElement element) {
        return element.getFileName() != null && element.getLineNumber() >= 0;
      }
    };
  }

  /**
   * Creates a {@link StackElementFilter} by exclusion {@link Pattern patterns}
   *
   * @param excludes regular expressions matching {@link StackTraceElement} to filter out
   * @return the filter
   */
  public static StackElementFilter byPattern(final List<Pattern> excludes) {
    return new StackElementFilter() {

      @Override
      public boolean accept(StackTraceElement element) {
        if (!excludes.isEmpty()) {
          String classNameAndMethod = element.getClassName() + "." + element.getMethodName();
          for (Pattern exclusionPattern : excludes) {
            if (exclusionPattern.matcher(classNameAndMethod).find()) {
              return false;
            }
          }
        }
        return true;
      }
    };
  }

}
