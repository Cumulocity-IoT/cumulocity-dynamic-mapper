// package mqtt.mapping.util;

// import lombok.extern.slf4j.Slf4j;
// import mqtt.mapping.core.C8YAgent;

// import java.io.File;
// import java.lang.reflect.Method;
// import java.net.URL;
// import java.net.URLClassLoader;

// @Slf4j
// public class ClassLoaderUtil {

//     static final Method ADD_URL_METHOD;
//     static {
//         final Method addURL;

//         // open the classloader module for java9+ so it wont have a warning
//         try {
//             openUrlClassLoaderModule();
//         } catch (Throwable ignored) {
//             // ignore exception. Java 8 wont have the module, so it wont matter if we ignore
//             // it
//             // cause there will be no warning
//         }

//         try {
//             addURL = URLClassLoader.class.getDeclaredMethod("addURL", URL.class);
//             addURL.setAccessible(true);
//         } catch (NoSuchMethodException exception) {
//             throw new AssertionError(exception);
//         }

//         ADD_URL_METHOD = addURL;
//     }

//     private static void openUrlClassLoaderModule() throws Exception {
//         Class<?> moduleClass = Class.forName("java.lang.Module");
//         Method addOpensMethod = moduleClass.getMethod("addOpens", String.class,
//                 moduleClass);

//         Method getModuleMethod = Class.class.getMethod("getModule");
//         Object urlClassLoaderModule = getModuleMethod.invoke(URLClassLoader.class);

//         Object thisModule = getModuleMethod.invoke(C8YAgent.class);
//         Module thisTypedModule = (Module) thisModule;
//         log.info("This module: {}, {}", thisModule.getClass(),
//                 thisTypedModule.getName());

//         addOpensMethod.invoke(urlClassLoaderModule,
//                 URLClassLoader.class.getPackage().getName(), thisModule);
//     }

//     public static ClassLoader getClassLoader(String url, String extName) {
//         try {
//             Method method = URLClassLoader.class.getDeclaredMethod("addURL", URL.class);
//             if (!method.isAccessible()) {
//                 method.setAccessible(true);
//             }
//             method.trySetAccessible();
//             // URLClassLoader classLoader = new URLClassLoader(new URL[] {},
//             // ClassLoader.getSystemClassLoader());
//             URLClassLoader classLoader = new URLClassLoader(new URL[] {}, mqtt.mapping.App.class.getClassLoader());

//             // method.invoke(classLoader, new URL(url));
//             return classLoader;
//         } catch (Exception e) {
//             log.error("Error loading extension: " + extName, e);
//             return null;
//         }
//     }

//     public static ClassLoader getClassLoader(File file, String extName) {
//         try {
//             URL[] urls = { file.toURI().toURL() };
//             URLClassLoader classLoader = new URLClassLoader(urls, mqtt.mapping.App.class.getClassLoader());

//             // method.invoke(classLoader, new URL(url));
//             return classLoader;
//         } catch (Exception e) {
//             log.error("Error loading extension: " + extName, e);
//             return null;
//         }
//     }
// }
