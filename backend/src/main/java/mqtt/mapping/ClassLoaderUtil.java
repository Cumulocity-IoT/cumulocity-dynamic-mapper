package mqtt.mapping;

import lombok.extern.slf4j.Slf4j;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;

@Slf4j
public class ClassLoaderUtil {
  
    public static ClassLoader getClassLoader(String url, String extName) {
        try {
            Method method = URLClassLoader.class.getDeclaredMethod("addURL", URL.class);
            // if (!method.isAccessible()) {
            //     method.setAccessible(true);
            // }
            method.trySetAccessible();
            URLClassLoader classLoader = new URLClassLoader(new URL[] {}, ClassLoader.getSystemClassLoader());
            method.invoke(classLoader, new URL(url));
            return classLoader;
        } catch (Exception e) {
            log.error("Error loading extension: " +  extName, e);
            return null;
        }
    }  
}
