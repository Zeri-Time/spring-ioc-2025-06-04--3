package com.ll.framework.ioc;

import com.ll.framework.ioc.annotations.*;
import com.ll.standard.util.Ut;
import org.reflections.Reflections;
import org.reflections.scanners.Scanners;
import org.reflections.util.ConfigurationBuilder;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;

public class ApplicationContext {
    private final Map<String, Object> beans = new HashMap<>();
    private final Reflections reflections;

    public ApplicationContext(String basePackage) {
        this.reflections = new Reflections(
                new ConfigurationBuilder()
                        .forPackage(basePackage)
                        .setScanners(Scanners.TypesAnnotated) // 타입에 붙은 애노테이션만 스캔
        );
    }

    public void init() {
        // ─────────────────────────────────────────────────────────────
        // A-1) 스캔 대상 애노테이션 목록(불변 리스트)
        // ─────────────────────────────────────────────────────────────
        List<Class<?>> targetAnnotations = List.of(
                Component.class, Service.class, Repository.class, Configuration.class
        );

        // A-2) 후보 타입 수집: 대상 애노테이션이 “직접” 달린 타입들
        //     (LinkedHashSet: 중복 제거 + 선언/탐색 순서 보존)
        Set<Class<?>> candidates = new LinkedHashSet<>();
        for (Class<?> ann : targetAnnotations) {
            // 해당 애노테이션이 붙은 타입(Class)들을 전부 가져와 candidates에 합치기
            candidates.addAll(reflections.get(Scanners.TypesAnnotated.with(ann).asClass()));
        }

        // A-3) 실제로 new 가능한 “구체 클래스”만 남기기
        //     - 인터페이스 X, 애노테이션 타입 X, 추상 클래스 X
        List<Class<?>> concrete = candidates.stream()
                .filter(c -> !c.isInterface())
                .filter(c -> !c.isAnnotation())
                .filter(c -> !Modifier.isAbstract(c.getModifiers()))
                .toList();

        // A-4) 남은 타입들(아직 인스턴스화 못한 것들)
        Set<Class<?>> remaining = new LinkedHashSet<>(concrete);

        // A-5) 생성자 주입으로 인스턴스화(멀티패스):
        //     한 바퀴에서 하나라도 만들면 progressed=true.
        //     아무 것도 못 만들고 남아 있으면 순환참조/해결불가로 판단.
        boolean progressed;
        do {
            progressed = false;

            Iterator<Class<?>> it = remaining.iterator();
            while (it.hasNext()) {
                Class<?> clazz = it.next();

                try {
                    // (a) 생성자들을 파라미터 수 내림차순으로 정렬
                    Constructor<?>[] ctors = clazz.getDeclaredConstructors();
                    Arrays.sort(ctors, Comparator
                            .comparingInt((Constructor<?> c) -> c.getParameterCount())
                            .reversed());

                    boolean created = false; // 이 클래스가 이번 라운드에 생성됐는지

                    // (b) 각 생성자별로 시도(파라미터를 beans에서 타입 매칭으로 해결)
                    for (Constructor<?> ctor : ctors) {
                        Class<?>[] paramTypes = ctor.getParameterTypes();
                        Object[] args = new Object[paramTypes.length];

                        boolean canSatisfy = true;

                        // 필요한 의존성(파라미터 타입)마다 현재까지 등록된 빈에서 대입 가능한 것을 찾음
                        for (int i = 0; i < paramTypes.length; i++) {
                            Class<?> neededType = paramTypes[i];

                            Object matched = null;
                            for (Object bean : beans.values()) {
                                if (neededType.isAssignableFrom(bean.getClass())) {
                                    matched = bean;
                                    break;
                                }
                            }

                            if (matched == null) {
                                // 이 생성자는 아직 만들 수 없음(필요한 빈이 아직 없기 때문)
                                canSatisfy = false;
                                break;
                            }
                            args[i] = matched;
                        }

                        if (!canSatisfy) {
                            // 다음(파라미터 수가 더 적은) 생성자 시도
                            continue;
                        }

                        // (c) 모든 의존성이 충족되면 인스턴스 생성
                        ctor.setAccessible(true);
                        Object instance = ctor.newInstance(args);

                        // (d) beanName 규칙: 클래스 simpleName의 첫 글자 소문자
                        String beanName = Ut.str.lcfirst(clazz.getSimpleName());

                        // (e) 등록
                        beans.put(beanName, instance);

                        // (f) 이번 라운드에 진전이 있었음을 표시하고 remaining에서 제거
                        created = true;
                        progressed = true;
                        it.remove();
                        break; // 이 클래스는 성공 → 다음 클래스로
                    }

                    // (g) 모든 생성자를 시도했는데도 생성 실패면, 다음 라운드에 재시도
                    if (!created) {
                        // do nothing
                    }

                } catch (Exception fatal) {
                    throw new RuntimeException("빈 생성 실패: " + clazz.getName(), fatal);
                }
            }

            // 한 바퀴 돌았는데 아무 것도 못 만들었고, 아직 남은 게 있으면 교착 상태
            if (!progressed && !remaining.isEmpty()) {
                throw new RuntimeException("순환참조 또는 의존성 해결 불가: " + remaining);
            }
        } while (!remaining.isEmpty());

        // ─────────────────────────────────────────────────────────────
        //  B) @Configuration 인스턴스들의 @Bean 메서드 처리
        // ─────────────────────────────────────────────────────────────

        // B-1) beans 안에서 @Configuration이 붙은 인스턴스들만 추출
        List<Object> configurationInstances = beans.values().stream()
                .filter(b -> b.getClass().isAnnotationPresent(Configuration.class))
                .toList();

        // B-2) @Bean 메서드 목록을 모아두고, 멀티패스로 처리
        //     - record를 내부 로컬 타입으로 선언(메서드 + 소유 config 인스턴스 보관)
        record BeanMethod(Object owner, Method method) {}

        List<BeanMethod> beanFactories = new ArrayList<>();

        // @Configuration 인스턴스들의 선언 메서드 중 @Bean 붙은 것 수집
        for (Object cfg : configurationInstances) {
            for (Method m : cfg.getClass().getDeclaredMethods()) {
                if (m.isAnnotationPresent(Bean.class)) { // ← 직접 만든 @Bean
                    beanFactories.add(new BeanMethod(cfg, m));
                }
            }
        }

        // B-3) @Bean 메서드들도 서로 의존할 수 있으므로 멀티패스로 호출
        boolean beanProgressed;
        do {
            beanProgressed = false;

            Iterator<BeanMethod> it = beanFactories.iterator();
            while (it.hasNext()) {
                BeanMethod bm = it.next();
                Object cfg = bm.owner();
                Method method = bm.method();

                // (a) 빈 이름 결정: @Bean(value) 있으면 그 값, 없으면 메서드명
                String beanName;
                Bean beanAnn = method.getAnnotation(Bean.class);
                if (beanAnn != null && !beanAnn.value().isEmpty()) {
                    beanName = beanAnn.value();
                } else {
                    beanName = method.getName();
                }

                if (beans.containsKey(beanName)) {
                    it.remove();
                    continue;
                }

                try {
                    // (b) 메서드 파라미터 주입(타입 기준으로 기존 beans에서 찾음)
                    Class<?>[] ptypes = method.getParameterTypes();
                    Object[] args = new Object[ptypes.length];

                    boolean canSatisfy = true;
                    for (int i = 0; i < ptypes.length; i++) {
                        Class<?> neededType = ptypes[i];

                        Object matched = null;
                        for (Object bean : beans.values()) {
                            if (neededType.isAssignableFrom(bean.getClass())) {
                                matched = bean;
                                break;
                            }
                        }

                        if (matched == null) {
                            // 아직 필요한 빈이 없으니 다음 라운드에 재시도
                            canSatisfy = false;
                            break;
                        }
                        args[i] = matched;
                    }

                    if (!canSatisfy) {
                        // 다음 BeanMethod 시도(이번 라운드에는 보류)
                        continue;
                    }

                    // (c) 모든 파라미터 충족 → 메서드 호출하여 제품(빈) 생성
                    method.setAccessible(true);
                    Object product = method.invoke(cfg, args);

                    if (product == null) {
                        throw new IllegalStateException("@Bean 메서드가 null을 반환했습니다: " + method);
                    }

                    // (d) 생성 결과를 싱글톤 빈으로 등록
                    beans.put(beanName, product);

                    // (e) 이번 라운드에 진전 발생 → 목록에서 제거
                    it.remove();
                    beanProgressed = true;

                } catch (Exception e) {
                    throw new RuntimeException("@Bean 생성 실패: " + method, e);
                }
            }

            if (!beanProgressed && !beanFactories.isEmpty()) {
                // 더 이상 진전이 없는데 처리할 @Bean 메서드가 남았음 → 순환참조/해결불가
                throw new RuntimeException("(@Bean) 순환참조 또는 의존성 해결 불가: " + beanFactories);
            }
        } while (!beanFactories.isEmpty());
        // 여기 도달하면:
        //  - 클래스 단위 빈(@Component/@Service/@Repository/@Configuration) 모두 생성 완료
        //  - @Configuration 내부의 @Bean 메서드 결과도 모두 등록 완료
    }

    public <T> T genBean(String beanName) {
        return (T) beans.get(beanName);
    }
}
