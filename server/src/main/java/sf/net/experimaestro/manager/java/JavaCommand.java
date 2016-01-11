package sf.net.experimaestro.manager.java;

import net.bpiwowar.experimaestro.tasks.Runner;
import sf.net.experimaestro.manager.json.Json;
import sf.net.experimaestro.manager.json.JsonObject;
import sf.net.experimaestro.manager.json.JsonString;
import sf.net.experimaestro.scheduler.Command;
import sf.net.experimaestro.scheduler.Commands;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

/**
 *
 */
public class JavaCommand implements JavaCommandBuilder {
    private Path[] classpath;

    public JavaCommand(Path[] classpath) {
        this.classpath = classpath;
    }

    @Override
    public Commands build(String taskClassname, JsonObject json) {
        final Command command = new Command();

        Command classpath = new Command();
        final Commands commands = new Commands(command);

        Arrays.asList(this.classpath).stream().forEach(f -> {
            classpath.add(new Command.Path(f));
            classpath.add(new Command.String(":"));
        });

        command.add("java", "-cp");
        command.add(classpath);

        // Sets JVM options
        final Json jvm = json.get(JavaTaskFactory.JVM_OPTIONS);
        if (jvm != null && jvm instanceof JsonObject) {
            final Json memory = ((JsonObject) jvm).get("memory");
            if (memory instanceof JsonString) {
                final Object s = memory.get();
                command.add("-Xmx" + s);
            }
        }

        // TaskReference class name
        command.add(taskClassname);

        // Runner class name
        command.add(Runner.class.getName());

        // Working directory
        command.add(Command.WorkingDirectory.INSTANCE);

        // Parameter file
        command.add(new Command.JsonParameterFile("json", json));

        return commands;
    }

    @Override
    public void setEnvironment(JsonObject json, Map<String, String> environment) {
        // do nothing
    }
}
