package io.temporal.activity;

import io.temporal.workflow.Workflow;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Indicates that an interface is an activity interface. Only interfaces annotated with this
 * annotation can be used as parameters to {@link Workflow#newActivityStub(Class)} methods.
 *
 * <p>Each method of the interface annotated with <code>ActivityInterface</code> including inherited
 * from interfaces is a separate activity. By default, the name of an activity type is its method
 * name with the first letter capitalized. Use {@link ActivityInterface#namePrefix()} or {{@link
 * ActivityMethod#name()}} to make sure that activity type names are distinct.
 *
 * <p>Example:
 *
 * <pre><code>
 *  public interface A {
 *      a();
 *  }
 *
 * {@literal @}ActivityInterface(namePrefix = "B_")
 *  public interface B extends A {
 *     b();
 *  }
 *
 * {@literal @}ActivityInterface(namePrefix = "C_")
 *  public interface C extends B {
 *     c();
 *  }
 *
 *  public class CImpl implements C {
 *      public void a() {}
 *      public void b() {}
 *      public void c() {}
 *  }
 * </code></pre>
 *
 * When <code>CImpl</code> instance is registered with the {@link io.temporal.worker.Worker} the
 * following activities are registered:
 *
 * <p>
 *
 * <ul>
 *   <li>B_a
 *   <li>B_b
 *   <li>C_c
 * </ul>
 *
 * The workflow code can call activities through stubs to <code>B
 * </code> and <code>C</code> interfaces. A call to crate stub to <code>A</code> interface will fail
 * as <code>A</code> is not annotated with ActivityInterface.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ActivityInterface {
  /**
   * Prefix to prepend to method names to generate activity types. Default is empty string which
   * means that method names are used as activity types.
   *
   * <p>Note that this value is ignored if a name of an activity is specified explicitly through
   * {@link ActivityMethod#name()}.
   *
   * <p>Be careful about names that contain special characters. These names can be used as metric
   * tags. And systems like prometheus ignore metrics which have tags with unsupported characters.
   */
  String namePrefix() default "";
}
