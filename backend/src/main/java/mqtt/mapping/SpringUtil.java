// package mqtt.mapping;

// import lombok.extern.slf4j.Slf4j;
// import org.springframework.beans.BeansException;
// import org.springframework.beans.factory.support.BeanDefinitionBuilder;
// import org.springframework.beans.factory.support.DefaultListableBeanFactory;
// import org.springframework.context.ApplicationContext;
// import org.springframework.context.ApplicationContextAware;
// import org.springframework.context.ConfigurableApplicationContext;
// import org.springframework.stereotype.Component;

// @Slf4j
// @Component
// public class SpringUtil implements ApplicationContextAware {
//     private DefaultListableBeanFactory defaultListableBeanFactory;

//     private ApplicationContext applicationContext;

//     @Override
//     public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
//         log.info("get applicationContext");
//         this.applicationContext = applicationContext;
//         // convert applicationContext to ConfigurableApplicationContext
//         ConfigurableApplicationContext configurableApplicationContext = (ConfigurableApplicationContext) applicationContext;
//         // get the bean factory and convert to DefaultListableBeanFactory
//         this.defaultListableBeanFactory = (DefaultListableBeanFactory) configurableApplicationContext.getBeanFactory();
//         log.info("get BeanFactory Success.");
//     }

//     /**
//      * Register bean in spring container
//      *
//      * @param beanName name
//      * @param clazz    class
//      */
//     public void registerBean(String beanName, Class<?> clazz) {
//         // create bean definitions via BeanDefinitionBuilder
//         BeanDefinitionBuilder beanDefinitionBuilder = BeanDefinitionBuilder.genericBeanDefinition(clazz);
//         // register bean
//         defaultListableBeanFactory.registerBeanDefinition(beanName, beanDefinitionBuilder.getRawBeanDefinition());
//         log.info("register bean [{}],Class [{}] success.", beanName, clazz);
//     }

//     public Object getBean(String name) {
//         return applicationContext.getBean(name);
//     }
// }
