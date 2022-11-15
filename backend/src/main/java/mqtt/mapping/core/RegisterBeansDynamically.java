package mqtt.mapping.core;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.stereotype.Service;

@Service
public class RegisterBeansDynamically implements BeanFactoryAware {

    private ConfigurableBeanFactory beanFactory;

    public <T> void registerBean(String beanName, T bean) {
        beanFactory.registerSingleton(beanName, bean);
    }

    @Override
    public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
        this.beanFactory = (ConfigurableBeanFactory) beanFactory;
    }
}
