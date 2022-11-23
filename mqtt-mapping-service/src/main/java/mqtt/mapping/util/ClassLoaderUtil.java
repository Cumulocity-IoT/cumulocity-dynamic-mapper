package mqtt.mapping.util;

import lombok.extern.slf4j.Slf4j;
import mqtt.mapping.App;

import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.List;

@Slf4j
public class ClassLoaderUtil {

    public static ClassLoader getClassLoader(String url, String extName) {
        try {
            Method method = URLClassLoader.class.getDeclaredMethod("addURL", URL.class);
            if (!method.isAccessible()) {
                method.setAccessible(true);
            }
            method.trySetAccessible();
            // URLClassLoader classLoader = new URLClassLoader(new URL[] {},
            // ClassLoader.getSystemClassLoader());
            URLClassLoader classLoader = new URLClassLoader(new URL[] {}, mqtt.mapping.App.class.getClassLoader());

            // method.invoke(classLoader, new URL(url));
            return classLoader;
        } catch (Exception e) {
            log.error("Error loading extension: " + extName, e);
            return null;
        }
    }

    public static ClassLoader getClassLoader(File file, String extName) {
        try {
            List<File> jars = Arrays.asList(file);
            URL[] urls = new URL[1];
            for (int i = 0; i < jars.size(); i++) {
                try {
                    urls[i] = jars.get(i).toURI().toURL();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            URLClassLoader classLoader = new URLClassLoader(urls, mqtt.mapping.App.class.getClassLoader());

            // method.invoke(classLoader, new URL(url));
            return classLoader;
        } catch (Exception e) {
            log.error("Error loading extension: " + extName, e);
            return null;
        }
    }
}
