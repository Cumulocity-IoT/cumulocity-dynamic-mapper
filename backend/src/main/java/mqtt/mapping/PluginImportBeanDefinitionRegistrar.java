// package mqtt.mapping;

// import lombok.SneakyThrows;
// import lombok.extern.slf4j.Slf4j;
// import org.springframework.beans.factory.config.BeanDefinition;
// import org.springframework.beans.factory.support.BeanDefinitionBuilder;
// import org.springframework.beans.factory.support.BeanDefinitionRegistry;
// import org.springframework.context.EnvironmentAware;
// import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
// import org.springframework.core.env.Environment;
// import org.springframework.core.type.AnnotationMetadata;

// /**
//  * 启动时注册bean
//  *
//  */
// @Slf4j
// public class PluginImportBeanDefinitionRegistrar implements ImportBeanDefinitionRegistrar {
//     /**
//      * jar address
//      */
//     private String targetUrl;
//     /**
//      * absolute plugin class path
//      */
//     private String pluginClass;

//     @SneakyThrows
//     @Override
//     public void registerBeanDefinitions(AnnotationMetadata importingClassMetadata, BeanDefinitionRegistry registry) {
//         ClassLoader classLoader = ClassLoaderUtil.getClassLoader(targetUrl);
//         Class<?> clazz = classLoader.loadClass(pluginClass);

//         BeanDefinitionBuilder builder = BeanDefinitionBuilder.genericBeanDefinition(clazz);
//         BeanDefinition beanDefinition = builder.getBeanDefinition();
//         registry.registerBeanDefinition(clazz.getName(), beanDefinition);
//         log.info("register bean [{}],Class [{}] success.", clazz.getName(), clazz);
//     }
// }
