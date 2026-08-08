# Spring Invocation Binding and DI semantics for Stage 2

Status: accepted research input for GitHub Issue #9  
Scope: Spring MVC, Spring bean injection, servlet filters, scheduling, Spring Boot runners, and Spring application events. This note changes no production behavior.

This note separates framework-documented behavior from GraphCRUD inference. Evidence classifications below are project decisions derived from the evidence-first domain model, not claims made by Spring or Jakarta.

## Conclusions

Stage 2 can confirm an Invocation Binding only when the source declares the framework hook, the declaring type is statically supported as a managed component, all annotation values needed for identity are compile-time recoverable, and DI resolves to one non-recovered candidate. A finite set of valid DI or dispatch candidates is `possible`. Missing metadata, runtime conditions, custom framework extensions, or an unbounded candidate set is `unresolved`; none may be promoted by name similarity.

## 1. HTTP mappings and stereotypes

### Documented facts

- Spring MVC uses `@RequestMapping` at type level for shared mappings and at method level to narrow a handler. `@GetMapping`, `@PostMapping`, `@PutMapping`, `@DeleteMapping`, and `@PatchMapping` are composed, HTTP-method-specific variants. Type and method paths therefore compose into one handler mapping. [`@RequestMapping` reference](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-requestmapping.html)
- Request mappings can also constrain parameters, headers, consumed media types, and produced media types. Method-level `consumes` and `produces` replace rather than extend corresponding type-level declarations. [`@RequestMapping` reference](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-requestmapping.html)
- Custom composed mapping annotations are meta-annotated with `@RequestMapping` and may redeclare its attributes. Multiple direct or composed `@RequestMapping` annotations on the same element are not combined: Spring logs a warning and uses only the first mapping. [`@RequestMapping` custom annotations](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-requestmapping.html#webmvc-ann-requestmapping-composed)
- `@Component` makes a class eligible for annotation-based component scanning. An annotation meta-annotated with `@Component` is a stereotype; Spring's `@Service`, `@Controller`, and `@Repository` are examples. An explicit stereotype value can suggest the bean name, while otherwise the configured `BeanNameGenerator` applies. [`@Component` Javadoc](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/stereotype/Component.html), [component scanning reference](https://docs.spring.io/spring-framework/reference/core/beans/classpath-scanning.html)
- Component eligibility remains configuration-dependent: scanning has configured base packages and filters, a custom name generator is allowed, and a Spring `Condition` can veto registration immediately before a bean definition is registered. [component scanning reference](https://docs.spring.io/spring-framework/reference/core/beans/classpath-scanning.html), [`Condition` Javadoc](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/context/annotation/Condition.html)

### GraphCRUD inference

- Confirm an HTTP Invocation Source to handler binding for the initial allowlist when a supported controller stereotype and one unambiguous direct/composed mapping yield compile-time constant method and path attributes. Preserve type- and method-level source anchors as separate Evidence Occurrences for the same canonical binding.
- Do not model `@Controller` or `@RestController` alone as an HTTP endpoint. A handler mapping is required.
- Treat multiple mapping annotations on one element as `unresolved`, even though a particular Spring runtime may choose its first encountered mapping; reflection/metadata order is not a safe canonical identity.
- Treat dynamic placeholders, custom `RequestCondition` implementations, unknown component-scan filters, conditional bean registration, and unresolved meta-annotations as `unresolved`. A finite set of statically evaluated paths or methods may be `possible` only when every candidate is retained deterministically.

## 2. Constructor injection, `@Autowired`, and `@Resource`

### Documented facts

- A bean class with one constructor uses it even without `@Autowired`. With multiple constructors, an annotated required constructor is selected; multiple optional annotated constructors are candidates and Spring chooses the one with the greatest number of satisfiable dependencies. If none is satisfiable, a primary/default constructor may be used. [`@Autowired` reference](https://docs.spring.io/spring-framework/reference/core/beans/annotation-config/autowired.html)
- `@Autowired` supports constructors, fields, setters, and arbitrary multi-argument methods. Single-valued injection is primarily type-based; qualifiers narrow type-selected candidates, while primary/fallback markers and, in a non-unique case, an injection-point-name match can affect selection. Optional forms include `required=false`, `Optional`, and nullable parameters. [`@Autowired` reference](https://docs.spring.io/spring-framework/reference/core/beans/annotation-config/autowired.html), [qualifiers reference](https://docs.spring.io/spring-framework/reference/core/beans/annotation-config/autowired-qualifiers.html)
- `@Resource` is supported on fields and bean-property setter methods. With an explicit name it has by-name semantics. Without an explicit name, Spring derives the field/property name, tries that bean name, and may fall back to a primary type match; it also resolves specified well-known infrastructure dependencies by type. [`@Resource` reference](https://docs.spring.io/spring-framework/reference/core/beans/annotation-config/resource.html)
- These annotations are processed by bean post-processors and only apply to objects managed by the relevant Spring context. [`@Autowired` reference](https://docs.spring.io/spring-framework/reference/core/beans/annotation-config/autowired.html)

### GraphCRUD inference

Resolve injection in this order without instantiating a context:

1. establish that the consumer and candidate are within the statically supported managed-bean set;
2. select the injection mechanism and constructor according to the documented rules;
3. collect assignable candidates using non-recovered Java bindings;
4. apply explicit `@Resource` name, qualifiers, primary/fallback metadata, and only then documented injection-point-name fallback;
5. emit `confirmed` only for one remaining candidate, `possible` for a deterministic finite candidate set, or `unresolved` with raw type/name and a stable reason when metadata is missing or runtime-dependent.

Custom `BeanFactoryPostProcessor`, `BeanPostProcessor`, `BeanNameGenerator`, factory-method runtime type, profiles/conditions, external XML not in the Analysis Project, and programmatic registrations can change the candidate set. Unless fully represented by an allowlisted static adapter, they cap the result at `unresolved`. Collection/array/map injection represents multiple intentional targets and must not be collapsed to one.

## 3. Framework invocation sources

### Servlet filters

The Jakarta Servlet container calls `Filter.doFilter` for each matching request/response pair. The supplied `FilterChain` invokes the next filter or terminal resource; a filter may deliberately omit `chain.doFilter` and block the chain. Filter selection and ordering depend on `@WebFilter` or deployment-descriptor mappings, URL/servlet-name matches, dispatcher configuration, and declaration order. [Jakarta Servlet 6.0 specification, chapter 6](https://jakarta.ee/specifications/servlet/6.0/jakarta-servlet-spec-6.0), [`FilterChain` Javadoc](https://jakarta.ee/specifications/servlet/6.1/apidocs/jakarta.servlet/jakarta/servlet/filterchain)

GraphCRUD may confirm a container Invocation Binding to a resolved `doFilter` implementation when an allowlisted registration is statically present. It must not assert that a request reaches the next filter/controller merely because the method accepts a `FilterChain`; only a resolved `chain.doFilter(...)` call supports that edge. Dynamic registration, conditional registration, unresolved URL patterns, and runtime chain ordering are `possible` or `unresolved` as bounded evidence permits.

### Scheduled methods

`@Scheduled` support must be enabled, for example with `@EnableScheduling`. A scheduled method has no arguments; synchronous return values are ignored. The annotation is repeatable, and co-located schedules may overlap. Reactive return types have additional adapter and deferred-subscription rules. [`@Scheduled` reference](https://docs.spring.io/spring-framework/reference/integration/scheduling.html), [`@Scheduled` Javadoc](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/scheduling/annotation/Scheduled.html)

GraphCRUD may confirm an Invocation Binding to a valid, resolved synchronous `@Scheduled` method only when scheduling enablement and the managed bean are statically supported. Placeholder/SpEL-like configuration values, disabled cron values, conditional beans, custom schedulers, or reactive/Kotlin forms outside the initial allowlist are `unresolved`. Repeated schedules are separate source evidence for the same Code Entrypoint, not duplicate canonical relationships.

### Startup runners

Spring Boot calls the single `run` method of managed `ApplicationRunner` and `CommandLineRunner` beans just before `SpringApplication.run(...)` completes. Ordering can be expressed with `Ordered` or `@Order`. [Spring Boot runner reference](https://docs.spring.io/spring-boot/reference/features/spring-application.html#features.spring-application.command-line-runner)

GraphCRUD may confirm the framework Invocation Binding to the uniquely resolved `run` override of a statically supported managed runner. It should not infer an ordering edge from interface implementation alone. Conditional registration, programmatic beans, and unresolved generic/type bindings remain `unresolved`.

### Application event listeners

`@EventListener` marks a managed-bean method as a listener. One event type may come from its parameter; one or more event classes may instead be declared in `classes`/`value`. The `condition` attribute is a runtime SpEL predicate. Listeners are synchronous by default, but the application event multicaster can be configured differently; lazy beans are not registered merely for listener discovery. [`@EventListener` Javadoc](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/context/event/EventListener.html), [application events reference](https://docs.spring.io/spring-framework/reference/core/beans/context-introduction.html#context-functionality-events)

GraphCRUD may confirm an event-type Invocation Binding when the managed listener and one event type are uniquely resolved and there is no runtime condition. Multiple explicitly declared event types produce distinct confirmed bindings. A non-empty condition means invocation for a particular publication is runtime-dependent and therefore `possible`, with the raw condition retained. Custom multicaster configuration changes timing, not the existence of a statically registered listener, but missing registration metadata or unresolved event types are `unresolved`.

## 4. Evidence decision table

| Static outcome | Maximum evidence | Required retention |
| --- | --- | --- |
| Supported framework declaration, managed bean, constant attributes, unique non-recovered target | `confirmed` | canonical Invocation Source/Code Entrypoint, mapping or event type, all declaration anchors |
| Multiple finite valid DI implementations or dispatch targets | `possible` | sorted bounded candidates and the reason uniqueness failed |
| Runtime event condition with otherwise resolved listener | `possible` | listener, event type, raw condition, source anchor |
| Conditional/profile-driven bean, custom scanner/condition, dynamic registration, unresolved placeholder | `unresolved` | raw annotation/configuration, source anchor, stable reason |
| Null/recovered Java or annotation binding | `unresolved` | raw identifier, bounded candidates if available, relevant compiler problem |
| Candidate/mapping bound exceeded | `unresolved` and partial analysis | retained prefix, total observed count, configured bound, source anchor |

Suggested stable reasons are `SPRING_MAPPING_AMBIGUOUS`, `SPRING_MAPPING_DYNAMIC`, `SPRING_BEAN_CONDITIONAL`, `SPRING_DI_AMBIGUOUS`, `SPRING_DI_METADATA_MISSING`, `SPRING_FILTER_REGISTRATION_UNKNOWN`, `SPRING_SCHEDULING_NOT_ENABLED`, and `SPRING_EVENT_CONDITION_RUNTIME`.

## 5. Stage 2 implementation constraints

- Keep Spring annotation traversal, merged/composed-annotation handling, bean candidate indexing, and framework-specific bounds inside the Java analyzer adapter. The application seam remains JDT- and Spring-neutral.
- Do not start a Spring container, execute customer configuration, or run build tools. That would violate the offline/static-analysis ADR.
- Emit canonical Relationship Assertions once and retain every type-level, method-level, injection-point, and registration observation as a separate Evidence Occurrence.
- A partial result remains queryable by snapshot ID and must not automatically replace the Active Snapshot.

## Issue #10 readiness

Issue #10 is unblocked by this research. The initial implementation can safely limit itself to direct and composed constant mappings on statically supported controller stereotypes, with all custom conditions, ambiguous annotations, unresolved meta-annotations, and runtime placeholders explicitly degraded rather than guessed.
