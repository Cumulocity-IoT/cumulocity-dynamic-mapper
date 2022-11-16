package mqtt.mapping;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Properties;

public class ClassLoaderTest {
    

    public static void main(String[] args) throws IOException, ClassNotFoundException {
    

      //  URL[] classLoaderUrls = new URL[]{new URL("file:///var/folders/4r/yw46h1sd3zxd348lws13d86r0000gn/T/mqtt.mapping.extension-1.015164594147540685846jar")};
        //URL[] classLoaderUrls = new URL[]{new URL("file:///Users/ck/work/git/cumulocity-dynamic-mqtt-mapper/extension/target/mqtt.mapping.extension-1.0.jar")};
    URL[] classLoaderUrls = new URL[]{new URL("file:///Users/ck/work/git/cumulocity-dynamic-mqtt-mapper/extension/target/mqtt-mapping-extension.jar")};
    URLClassLoader classLoader = URLClassLoader.newInstance(classLoaderUrls);

    InputStream resourceAsStream = classLoader.getResourceAsStream("extension.properties");
    BufferedReader buffered = new BufferedReader(new InputStreamReader(resourceAsStream));
    Properties props = new Properties();
    // try {
        String line;
        while ((line = buffered.readLine()) != null) {
        System.out.println("---------------" + line);
        }
        if (buffered != null)
            props.load(buffered);
    // } catch (IOException io) {
    //     io.printStackTrace();
    // }
    Class<?> clazz = classLoader.loadClass("mqtt.mapping.processor.protobuf.CustomEventOuter");
    //Class<?> clazz = classLoader.loadClass("mqtt.Test");
    System.out.println("Found:" + clazz.getName());
 }
}