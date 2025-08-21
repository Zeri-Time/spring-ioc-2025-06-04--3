package com.ll.framework.ioc;

import com.ll.framework.ioc.annotations.Bean;
import com.ll.framework.ioc.annotations.Component;
import com.ll.framework.ioc.annotations.Configuration;
import com.ll.standard.util.Ut;
import org.reflections.Reflections;
import org.reflections.scanners.SubTypesScanner;
import org.reflections.util.ConfigurationBuilder;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.*;

public class ApplicationContext {

    private final String basePackage;
    private final Map<String, Object> beans = new HashMap<>();

    public ApplicationContext(String basePackage) {
        this.basePackage = basePackage;
    }

    public void init() {
        Reflections reflections = new Reflections(
                new ConfigurationBuilder()
                        .forPackages(basePackage)
                        .addScanners(new SubTypesScanner(false))
        );

        Set<Class<?>> allClasses = reflections.getSubTypesOf(Object.class);

        Set<Class<?>> remaining = new HashSet<>();
        for (Class<?> clazz : allClasses) {
            if (clazz.isInterface()) continue;
            if (clazz.isAnnotationPresent(Configuration.class)) {
                try {
                    processConfigurationClass(clazz);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                continue;
            }
            if (hasComponentAnnotation(clazz)) {
                remaining.add(clazz);
            }
        }

        while (!remaining.isEmpty()) {
            Iterator<Class<?>> iterator = remaining.iterator();
            while (iterator.hasNext()) {
                Class<?> clazz = iterator.next();
                try {
                    Object instance = createInstance(clazz);
                    if (instance != null) {
                        String beanName = Ut.str.lcfirst(clazz.getSimpleName());
                        beans.put(beanName, instance);
                        iterator.remove();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private boolean hasComponentAnnotation(Class<?> clazz) {
        for (Annotation annotation : clazz.getAnnotations()) {
            Class<? extends Annotation> annoType = annotation.annotationType();
            if (annoType.equals(Component.class)) return true;
            if (annoType.isAnnotationPresent(Component.class)) return true;
        }
        return false;
    }

    private Object createInstance(Class<?> clazz) throws Exception {
        Constructor<?>[] constructors = clazz.getConstructors();

        if (constructors.length == 0) {
            return null;
        }

        Constructor<?> constructor = constructors[0];
        Class<?>[] paramTypes = constructor.getParameterTypes();

        if (paramTypes.length == 0) {
            return constructor.newInstance();
        }

        Object[] params = new Object[paramTypes.length];
        for (int i = 0; i < paramTypes.length; i++) {
            String depBeanName = Ut.str.lcfirst(paramTypes[i].getSimpleName());
            Object depBean = beans.get(depBeanName);
            if (depBean == null) {
                return null;
            }
            params[i] = depBean;
        }

        return constructor.newInstance(params);
    }

    private void processConfigurationClass(Class<?> configClazz) throws Exception {
        Object configInstance = createInstance(configClazz);

        Set<Method> remaining = new HashSet<>();
        for (Method method : configClazz.getDeclaredMethods()) {
            if (method.isAnnotationPresent(Bean.class)) {
                remaining.add(method);
            }
        }

        Map<Class<?>, String> typeToBeanName = new HashMap<>();
        for (Method method : remaining) {
            typeToBeanName.put(method.getReturnType(), method.getName());
        }

        while (!remaining.isEmpty()) {
            Iterator<Method> iterator = remaining.iterator();
            while (iterator.hasNext()) {
                Method method = iterator.next();
                Class<?>[] paramTypes = method.getParameterTypes();
                Object[] params = new Object[paramTypes.length];
                boolean canCreate = true;

                for (int i = 0; i < paramTypes.length; i++) {
                    String beanName = typeToBeanName.get(paramTypes[i]);
                    if (beanName == null) {
                        canCreate = false;
                        break;
                    }
                    Object bean = beans.get(beanName);
                    if (bean == null) {
                        canCreate = false;
                        break;
                    }
                    params[i] = bean;
                }

                if (canCreate) {
                    Object bean = method.invoke(configInstance, params);
                    beans.put(method.getName(), bean);
                    iterator.remove();
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    public <T> T genBean(String beanName) {
        return (T) beans.get(beanName);
    }
}
