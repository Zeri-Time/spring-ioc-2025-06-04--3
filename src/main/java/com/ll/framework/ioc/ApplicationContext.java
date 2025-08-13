package com.ll.framework.ioc;

import com.ll.framework.ioc.annotations.Bean;
import com.ll.framework.ioc.annotations.Configuration;
import com.ll.framework.ioc.annotations.Repository;
import com.ll.framework.ioc.annotations.Service;
import org.reflections.Reflections;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.*;
import java.util.stream.Collectors;

public class ApplicationContext {
    // 모든 빈 인스턴스를 저장하는 Map (싱글톤 보장)
    private final Map<String, Object> beans = new HashMap<>();
    private final Reflections reflections;

    public ApplicationContext(String basePackage) {
        // Reflections 라이브러리를 사용하여 basePackage 하위 클래스들을 스캔
        this.reflections = new Reflections(basePackage);
    }

    public void init() {
        // 1. @Configuration 클래스 처리
        Set<Class<?>> configClasses = reflections.getTypesAnnotatedWith(Configuration.class);
        for (Class<?> configClass : configClasses) {
            try {
                // 설정 클래스(@Configuration) 인스턴스 생성
                Object configInstance = createBeanInstance(configClass);

                // @Bean 메서드 실행하여 빈 등록
                for (Method method : configClass.getDeclaredMethods()) {
                    if (method.isAnnotationPresent(Bean.class)) {
                        String beanName = method.getName(); // 메서드 이름 = 빈 이름
                        if (!beans.containsKey(beanName)) {
                            // @Bean 메서드 호출로 빈 생성
                            Object bean = createBeanFromMethod(configInstance, method);
                            beans.put(beanName, bean);
                        }
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException("Configuration 처리 중 오류: " + configClass.getName(), e);
            }
        }

        // 2. @Service, @Repository 클래스 처리
        // @Service 클래스 수집
        List<Class<?>> beanClasses = reflections.getTypesAnnotatedWith(Service.class)
                .stream().collect(Collectors.toList());
        // @Repository 클래스 추가
        beanClasses.addAll(reflections.getTypesAnnotatedWith(Repository.class));

        // 수집된 클래스들 빈으로 생성
        for (Class<?> beanClass : beanClasses) {
            String beanName = getBeanName(beanClass);
            createBean(beanClass, beanName);
        }
    }

    /**
     * @Bean 메서드를 실행하여 빈을 생성하는 메서드
     */
    private Object createBeanFromMethod(Object configInstance, Method method) throws Exception {
        Class<?>[] paramTypes = method.getParameterTypes();
        Object[] dependencies = new Object[paramTypes.length];

        // 메서드 파라미터(의존성) 처리
        for (int i = 0; i < paramTypes.length; i++) {
            String depName = getBeanName(paramTypes[i]);
            // 의존 빈이 없으면 먼저 생성
            if (!beans.containsKey(depName)) {
                createBean(paramTypes[i], depName);
            }
            dependencies[i] = beans.get(depName);
        }
        // @Bean 메서드 호출하여 빈 인스턴스 반환
        return method.invoke(configInstance, dependencies);
    }

    /**
     * 주어진 클래스 타입과 이름으로 빈을 생성하여 등록
     */
    private void createBean(Class<?> beanClass, String beanName) {
        // 이미 존재하는 빈이면 생성하지 않음 (싱글톤 보장)
        if (beans.containsKey(beanName)) return;

        try {
            Object beanInstance = createBeanInstance(beanClass);
            beans.put(beanName, beanInstance);
        } catch (Exception e) {
            throw new RuntimeException("빈 생성 중 오류: " + beanClass.getName(), e);
        }
    }

    /**
     * 생성자를 통해 빈 인스턴스 생성
     * 생성자 파라미터는 자동으로 의존성 주입
     */
    private Object createBeanInstance(Class<?> beanClass) throws Exception {
        Constructor<?>[] constructors = beanClass.getConstructors();
        if (constructors.length != 1) {
            throw new IllegalStateException("빈은 하나의 public 생성자만 가져야 합니다.");
        }
        Constructor<?> constructor = constructors[0];

        // 생성자 파라미터 타입 추출
        Class<?>[] parameterTypes = constructor.getParameterTypes();
        Object[] dependencies = new Object[parameterTypes.length];

        // 필요한 의존성을 먼저 생성 후 주입
        for (int i = 0; i < parameterTypes.length; i++) {
            String dependencyBeanName = getBeanName(parameterTypes[i]);
            if (!beans.containsKey(dependencyBeanName)) {
                createBean(parameterTypes[i], dependencyBeanName);
            }
            dependencies[i] = beans.get(dependencyBeanName);
        }

        // 최종 인스턴스 생성
        return constructor.newInstance(dependencies);
    }

    /**
     * 클래스명에서 첫 글자만 소문자로 변환하여 빈 이름 생성
     * 예: TestPostService -> testPostService
     */
    private String getBeanName(Class<?> beanClass) {
        String className = beanClass.getSimpleName();
        return Character.toLowerCase(className.charAt(0)) + className.substring(1);
    }

    /**
     * 빈 이름으로 빈 인스턴스를 반환
     */
    public <T> T genBean(String beanName) {
        return (T) beans.get(beanName);
    }
}
