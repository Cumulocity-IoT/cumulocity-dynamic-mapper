/*
 * Copyright (c) 2022 Software AG, Darmstadt, Germany and/or Software AG USA Inc., Reston, VA, USA,
 * and/or its subsidiaries and/or its affiliates and/or their licensors.
 *
 * SPDX-License-Identifier: Apache-2.0
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
 *
 * @authors Christof Strack, Stefan Witschel
 */

package dynamic.mapping;

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
    Class<?> clazz = classLoader.loadClass("dynamic.mapping.processor.protobuf.CustomEventOuter");
    //Class<?> clazz = classLoader.loadClass("mqtt.Test");
    System.out.println("Found:" + clazz.getName());
 }
}