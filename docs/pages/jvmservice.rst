=====================================
Creating a Service From JVM Classpath
=====================================

In case your application is using a JVM-based programming language and is able to include Java libraries, you can use the
current JVM classpath to create a service.

.. code-block:: java
    :linenos:

    new Deployment.builder("sample-multithread")
                .withServiceFromJvmClasspath("s1", "target/classes", "**commons-io*.jar")
                    .startCommand("java -cp ${REDIT_JVM_CLASSPATH} io.redit.samples.multithread.Main")

This method will create a new service by adding all the paths included in the JVM classpath as library paths to your
service. Also, any relative, absolute or wildcard paths that comes after the service name, if exists in the class path,
will be added to the service as an instrumentable path. This method will return a ``ServiceBuilder`` object, as such, all
the regular service configurations are available.

As can be seen in line 3, the new classpath based on the new target paths is provided in the ``REDIT_JVM_CLASSPATH``
and can be used in the service's start command.