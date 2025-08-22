v2에서 v3로 가면서 추가된 코드들만 설명 하겠습니다.
<br>
https://github.com/Zeri-Time/spring-ioc-2025-08-11-v2

---

### @Configuration 이 붙은 클래스 필터링

```java
// init()
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
```

- 기존의 v2에서 if (clazz.isAnnotationPresent(Configuration.class) 부분이 추가되어
탐색한 클래스가 @Configuration 이 붙어있을 시
processConfigurationClass()로 별도 처리

### processConfigurationClass()

```java
// processConfigurationClass()
Object configInstance = createInstance(configClazz);

Set<Method> remaining = new HashSet<>();
for (Method method : configClazz.getDeclaredMethods()) {
    if (method.isAnnotationPresent(Bean.class)) {
        remaining.add(method);
    }
}
```

- @Configuration이 붙은 클래스는 해당 클레스의 @Bean 이 붙어있는 메서드를 호출하여 객체(Bean)을 생성해야 하기 때문에 해당 클래스 생성 후 해당 클래스로부터 메서드 정보들을 받아와서 @Bean 이 붙은 메서드 정보를 필터링, remaining 자료구조에 저장

```java
// processConfigurationClass()
Map<Class<?>, String> typeToBeanName = new HashMap<>();
for (Method method : remaining) {
    typeToBeanName.put(method.getReturnType(), method.getName());
}
```

- @Bean이 붙은 메서드는 빈 이름(Key) 값은 메서드 이름이지만 저장되는 객체(Bean) 타입은 return 타입인 클래스 이기 때문에 해당 정보를 매핑하기 위해 typeToBeanName으로 매핑정보 저장

```java
// processConfigurationClass()
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
```

- v2의 Bean 생성 방식과 동일하게 필터링 했던 remaining 자료구조의 메서드들이 모두 빈으로 등록되어 remaining이 비어있을때 까지 반복
- Class<?>[] paramTypes = method.getParameterTypes() 를 통해 Bean 생성을 위한 해당 메서드의 파라미터(의존성) 타입을 저장
- 파라미터로 들어갈 Bean(의존성) 들을 저장하기위해 Object[] params 배열 선언
- boolean canCreate는 현재 탐색중인 메서드를 호출하기위한 Bean(의존성)들이 beans 자료구조에 모두 등록 되어있는지 판별하는 boolean값
- 현재 메서드에 필요한 파라미터(의존성) 이 있는지 판단하고 있다면 가져오기위해 String beanName = typeToBeanName.get(paramTypes[i]) 으로 typeToBeanName 자료구조에 저장해 놓았던 매핑 정보를 통해 Bean 이름 가져옴
- 가져온 bean 이름이 없거나 bean이름으로 beans 자료구조에서 Bean을 찾을 수 없다면 아직 메서드를 호출하기위한 의존성이 준비가 안된 상태이기 때문에 canCreate = false 설정
- bean 이름으로 메서드 호출을 위한 모든 파라미터(Bean) 조회 성공 시 Object bean = method.invoke(configInstance, params) 로 해당 메서드를 호출하여 객체(Bean)을 반환받고 beans.put(method.getName(), bean) 을 통해 beans 자료구조에 메서드 이름을 Key 값으로 Bean 등록
- Bean으로 등록 성공한 메서드는 remaining 자료구조에서 삭제
