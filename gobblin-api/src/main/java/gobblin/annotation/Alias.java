/*
 * Copyright (C) 2014-2016 LinkedIn Corp. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a copy of the
 * License at  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied.
 */
package gobblin.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import gobblin.util.ClassAliasResolver;


/**
 * Denotes that a class has an alias.
 * Using {@link ClassAliasResolver#resolve(String)}, an alias can be resolved to cannonical name of the annotated class
 */
@Documented @Retention(value=RetentionPolicy.RUNTIME) @Target(value=ElementType.TYPE)
public @interface Alias {
  /**
   * Alias for that class
   */
  public String value();
  public String description() default "";
}
