package bpiwowar.expmanager.rsrc;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;

import bpiwowar.argparser.ListAdaptator;
import bpiwowar.expmanager.locks.Lock;
import bpiwowar.log.Logger;
import bpiwowar.utils.Output;

/**
 * A command line task (executed with the default shell)
 * 
 * @author B. Piwowarski <benjamin@bpiwowar.net>
 */
public class CommandLineTask extends Task {
	final static private Logger LOGGER = Logger.getLogger();

	/**
	 * The command to execute
	 */
	private String[] command;

	/**
	 * The shell command used
	 */
	final String shellCommand = "/bin/bash";

	/**
	 * Constructs the command line
	 * 
	 * @param taskManager
	 * @param identifier
	 * @param command
	 */
	public CommandLineTask(TaskManager taskManager, String identifier,
			String[] command) {
		super(taskManager, identifier);

		this.command = new String[] {
				shellCommand,
				"-c",
				String.format("( %s ) > %s.out 2> %2$s.err", Output.toString(
						" ", ListAdaptator.create(command),
						new Output.Formatter<String>() {
							public String format(String t) {
								StringBuilder sb = new StringBuilder();
								for (int i = 0; i < t.length(); i++) {
									final char c = t.charAt(i);
									switch (c) {
									case ' ':
										sb.append("\\ ");
										break;
									default:
										sb.append(c);
									}
								}
								return sb.toString();
							}
						}), identifier, identifier) };

	}

	@Override
	protected int doRun(ArrayList<Lock> locks) throws IOException,
			InterruptedException {
		// Runs the command
		LOGGER.info("Evaluating command %s", Arrays.toString(command));
		Process p = Runtime.getRuntime().exec(command);
		
		// Changing the ownership of the different logs
		final int pid = bpiwowar.expmanager.utils.PID.getPID(p);
		for (Lock lock : locks) {
			lock.changeOwnership(pid);
		}

		synchronized (p) {
			LOGGER.info("Waiting for the process (PID %d) to end", pid);
			int code = p.waitFor();
			if (code != 0)
				throw new RuntimeException("Process ended with errors (code "
						+ code + ")");
			LOGGER.info("Done");
			return code;
		}
	}
}
