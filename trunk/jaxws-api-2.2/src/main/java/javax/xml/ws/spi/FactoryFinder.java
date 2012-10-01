/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package javax.xml.ws.spi;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.PrivilegedAction;
import java.util.Properties;

import org.apache.geronimo.osgi.locator.ProviderLocator;

/**
 * This code is designed to implement the pluggability
 * feature and is designed to both compile and run on JDK version 1.1 and
 * later.  The code also runs both as part of an unbundled jar file and
 * when bundled as part of the JDK.
 * <p/>
 * This class is duplicated for each subpackage so keep it in sync.
 * It is package private and therefore is not exposed as part of the JAXRPC
 * API.
 */
class FactoryFinder {
    /**
     * Set to true for debugging.
     */
    private static final boolean debug = false;

    private static void debugPrintln(String msg) {
        if (debug) {
            System.err.println("Factory Finder:" + msg);
        }
    }

    /**
     * Figure out which ClassLoader to use.  For JDK 1.2 and later use
     * the context ClassLoader.
     *
     * @return the <code>ClassLoader</code>
     * @throws ConfigurationError if this class is unable to work with the
     *                            host JDK
     */
    private static ClassLoader findClassLoader()
            throws ConfigurationError {
        // REVIEW This doPriv block may be unnecessary because this method is private and
        // the caller already has a doPriv.  I added the doPriv in case someone changes the
        // visibility of this method to non-private.
        ClassLoader cl = (ClassLoader)
            doPrivileged( new PrivilegedAction<Object>() {
                public Object run() {

                    Method m = null;

                    try {

                        m = Thread.class.getMethod("getContextClassLoader", (Class []) null);
                    } catch (NoSuchMethodException e) {
                        // Assume that we are running JDK 1.1, use the current ClassLoader
                        debugPrintln("assuming JDK 1.1");
                        return FactoryFinder.class.getClassLoader();
                    }

                    try {
                        return (ClassLoader) m.invoke(Thread.currentThread(), (Object []) null);
                    } catch (IllegalAccessException e) {
                        // assert(false)
                        throw new ConfigurationError("Unexpected IllegalAccessException",
                                e);
                    } catch (InvocationTargetException e) {
                        // assert(e.getTargetException() instanceof SecurityException)
                        throw new ConfigurationError("Unexpected InvocationTargetException",
                                e);
                    }
                }
            }
        );
        return cl;

    }

    /**
     * Create an instance of a class using the specified
     * <code>ClassLoader</code>, or if that fails from the
     * <code>ClassLoader</code> that loaded this class.
     *
     * @param className   the name of the class to instantiate
     * @param classLoader a <code>ClassLoader</code> to load the class from
     * @return a new <code>Object</code> that is an instance of the class of
     *         the given name from the given class loader
     * @throws ConfigurationError if the class could not be found or
     *                            instantiated
     */
    private static Object newInstance(String className,
                                      ClassLoader classLoader)
            throws ConfigurationError {

        final ClassLoader iClassLoader = classLoader;
        final String iClassName = className;

        // REVIEW This doPriv block may be unnecessary because this method is private and
        // the caller already has a doPriv.  I added the doPriv in case someone changes the
        // visibility of this method to non-private.
        Object obj =
            doPrivileged( new PrivilegedAction<Object>() {
                public Object run() {
                    try {
                        return ProviderLocator.loadClass(iClassName, FactoryFinder.class, iClassLoader).newInstance();
                    } catch (ClassNotFoundException x) {
                        throw new ConfigurationError(
                                "Provider " + iClassName + " not found", x);
                    } catch (Exception x) {
                        throw new ConfigurationError(
                                "Provider " + iClassName + " could not be instantiated: " + x,
                                x);
                    }
                }
            });
        return obj;
    }

    /**
     * Finds the implementation Class object in the specified order.  Main
     * entry point.
     *
     * @param factoryId         Name of the factory to find, same as
     *                          a property name
     * @param fallbackClassName Implementation class name, if nothing else
     *                          is found.  Use null to mean no fallback.
     * @return Class object of factory, never null
     * @throws FactoryFinder.ConfigurationError
     *          Package private so this code can be shared.
     */
    static Object find(String factoryId, String fallbackClassName)
            throws ConfigurationError {

        final String iFactoryId = factoryId;
        final String iFallbackClassName = fallbackClassName;

        Object obj =
            doPrivileged( new PrivilegedAction<Object>() {
                public Object run() {
                    debugPrintln("debug is on");

                    // Section 6.2.1 of the jaxws spec gives lookup order as
                    // 1.  META-INF/services definition
                    // 2.  ${java.home}/lib/jaxws.properties file
                    // 3.  System property
                    // 4.  The default implementation class

                    ClassLoader classLoader = findClassLoader();
                    try {
                        // If we are deployed into an OSGi environment, leverage it
                        Class factoryClass = null;
                        if (FactoryFinder.class.getClassLoader() != null) {
                            factoryClass = FactoryFinder.class.getClassLoader().loadClass(iFactoryId);
                        } else {
                            factoryClass = Class.forName(iFactoryId);
                        }
                        Class spiClass = org.apache.servicemix.specs.locator.OsgiLocator.locate(factoryClass, iFactoryId);
                        if (spiClass != null) {
                            return spiClass.newInstance();
                        }
                     } catch (Throwable e) {
                        if (debug) e.printStackTrace();
                     }

                    try {
                        // check the META-INF/services definitions, and return it if
                        // we find something.
                        Object service = ProviderLocator.getService(iFactoryId, FactoryFinder.class, classLoader);
                        if (service != null) {
                            return service;
                        }
                    } catch (Exception ex) {
                        if (debug) ex.printStackTrace();
                    }

                    try {
                        String factoryClassName =  ProviderLocator.lookupByJREPropertyFile("lib" + File.separator + "jaxrpc.properties", iFactoryId);
                        if (factoryClassName != null) {
                            debugPrintln("found java.home property " + factoryClassName);
                            return newInstance(factoryClassName, classLoader);
                        }
                    } catch (Exception ex) {
                        if (debug) ex.printStackTrace();
                    }

                    // Use the system property first
                    try {
                        String systemProp =
                            System.getProperty(iFactoryId);
                        if (systemProp != null) {
                            debugPrintln("found system property " + systemProp);
                            return newInstance(systemProp, classLoader);
                        }
                    } catch (SecurityException se) {
                    }

                    if (iFallbackClassName == null) {
                        throw new ConfigurationError(
                                "Provider for " + iFactoryId + " cannot be found", null);
                    }

                    debugPrintln("loaded from fallback value: " + iFallbackClassName);
                    return newInstance(iFallbackClassName, classLoader);
                }
            });
        return obj;
    }

    private static <T> T doPrivileged(PrivilegedAction<T> action) {
        SecurityManager sm = System.getSecurityManager();
        if (sm == null) {
            return action.run();
        } else {
            return java.security.AccessController.doPrivileged(action);
        }
    }

    static class ConfigurationError extends Error {
        // fixme: should this be refactored to use the jdk1.4 exception
        // wrapping?

        private Exception exception;

        /**
         * Construct a new instance with the specified detail string and
         * exception.
         *
         * @param msg the Message for this error
         * @param x   an Exception that caused this failure, or null
         */
        ConfigurationError(String msg, Exception x) {
            super(msg);
            this.exception = x;
        }

        Exception getException() {
            return exception;
        }
    }
}
