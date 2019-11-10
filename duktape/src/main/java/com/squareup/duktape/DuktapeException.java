/*
 * Copyright (C) 2015 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.squareup.duktape;


import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DuktapeException extends RuntimeException {
  /**
   *
   */
  private static final long serialVersionUID = 1538523787401076917L;
  /**
   * Duktape stack trace strings have multiple lines of the format " at func
   * (file.ext:line)". "func" is optional, but we'll omit frames without a
   * function, since it means the frame is in native code.
   */
  private final static Pattern STACK_TRACE_PATTERN =
      Pattern.compile("\\s*at (\\[?)(.*?)]? \\((.*?):?(\\d+)?\\).*$");
  /** Java StackTraceElements require a class name.  We don't have one in JS, so use this. */
  private final static String STACK_TRACE_CLASS_NAME = "JavaScript";

  public DuktapeException(String detailMessage) {
    super(getErrorMessage(detailMessage));
    addDuktapeStack(this, detailMessage);
  }

  /**
   * Parses {@code StackTraceElement}s from {@code detailMessage} and adds them to the proper place
   * in {@code throwable}'s stack trace.  Note: this method is also called from native code.
   */
  static void addDuktapeStack(Throwable throwable, String detailMessage) {
    String[] lines = detailMessage.split("\n", -1);
    if (lines.length <= 1) {
      return;
    }
    // We have a stacktrace following the message.  Add it to the exception.
    List<StackTraceElement> elements = new ArrayList<>();

    // Splice the JavaScript stack in right above the call to Duktape.evaluate.
    StackTraceElement[] selfTrace = new Exception().getStackTrace();

    // depending on the platform, the exception tracem ay add the stack all the way up to the
    // exception constructor (meaning, this method, and the <init> call).
    StackTraceElement search0 = selfTrace[0];
    StackTraceElement search1 = selfTrace[1];
    StackTraceElement search2 = selfTrace[2];

    boolean spliced = false;
    for (StackTraceElement stackTraceElement : throwable.getStackTrace()) {
      if (!spliced && (stackTraceElement.equals(search0) || stackTraceElement.equals(search1) || stackTraceElement.equals(search2))) {
        spliced = true;
        for (int i = 1; i < lines.length; ++i) {
          StackTraceElement jsElement = toStackTraceElement(lines[i]);
          if (jsElement == null) {
            continue;
          }
          elements.add(jsElement);
        }
      }
      elements.add(stackTraceElement);
    }
    throwable.setStackTrace(elements.toArray(new StackTraceElement[elements.size()]));
  }

  static String addJavaStack(String detailMessage, Throwable throwable) {
    String[] parts = detailMessage.split("\n", 2);
    detailMessage = parts[1];
    StringBuilder ret = new StringBuilder(parts[0]);

    // Splice the JavaScript stack in right above the call to Duktape.evaluate.
    StackTraceElement[] selfTrace = new Exception().getStackTrace();

    // Splice the JavaScript stack in right above the call to Duktape.evaluate.
    // depending on the platform, the exception tracem ay add the stack all the way up to the
    // exception constructor (meaning, this method, and the <init> call).
    StackTraceElement search0 = selfTrace[0];
    StackTraceElement search1 = selfTrace[1];
    StackTraceElement search2 = selfTrace[2];
    
    boolean spliced = false;
    for (StackTraceElement stackTraceElement : throwable.getStackTrace()) {
      if (!spliced && (stackTraceElement.equals(search0) || stackTraceElement.equals(search1) || stackTraceElement.equals(search2))) {
        spliced = true;
        ret.append(detailMessage);
      }
      ret.append(String.format("\n    at [%s.%s] (%s:%s)", stackTraceElement.getClassName(), stackTraceElement.getMethodName(), stackTraceElement.getFileName(), stackTraceElement.getLineNumber()));
    }
    return ret.toString();
  }

  private static String getErrorMessage(String detailMessage) {
    int end = detailMessage.indexOf('\n');
    return end > 0
        ? detailMessage.substring(0, end)
        : detailMessage;
  }

  private static StackTraceElement toStackTraceElement(String s) {
    Matcher m = STACK_TRACE_PATTERN.matcher(s);
    if (!m.matches()) {
      // Nothing interesting on this line.
      return null;
    }
    int line = m.group(4) != null ? Integer.parseInt(m.group(4)) : 1;
    String className = m.group(1) == null ? STACK_TRACE_CLASS_NAME : "";

    return new StackTraceElement(className, m.group(2), m.group(3),
      line);
  }
}
