package net.tomp2p.holep.manual;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;

import net.tomp2p.connection.Bindings;
import net.tomp2p.p2p.Peer;
import net.tomp2p.p2p.PeerBuilder;
import net.tomp2p.peers.Number160;

public class LocalNATUtils {
	public static int executeNatSetup(String action, String... cmd) throws IOException, InterruptedException {
		String startDir = System.getProperty("user.dir");
		String[] cmds = new String[cmd.length + 3];
		cmds[0] = "/usr/bin/sudo";
		cmds[1] = startDir+"/src/test/resources/nat-net.sh";
		cmds[2] = action;
		for(int i=3;i<cmds.length;i++) {
			cmds[i] = cmd[i-3];
		}
		final ProcessBuilder builder = new ProcessBuilder(cmds);
		builder.redirectError(ProcessBuilder.Redirect.INHERIT);
		Process process = builder.start();
		process.waitFor();
		return process.exitValue();
	}

	public static Process executePeer(Class<?> klass, String nr, String... cmd) throws IOException, InterruptedException {
		String javaHome = System.getProperty("java.home");
		String javaBin = javaHome + File.separator + "bin" + File.separator
		        + "java";
		String classpath = System.getProperty("java.class.path");
		String className = klass.getCanonicalName();

		String[] cmds = new String[cmd.length + 10];
		cmds[0] = "sudo";
		cmds[1] = "ip";
		cmds[2] = "netns";
		cmds[3] = "exec";
		cmds[4] = "unr" + nr;
		cmds[5] = javaBin;
		cmds[6] = "-cp";
		cmds[7] = classpath;
		cmds[8] = className;
		cmds[9] = nr;
		for(int i=10;i<cmds.length;i++) {
			cmds[i] = cmd[i-10];
		}
		
		ProcessBuilder builder = new ProcessBuilder(cmds);
		builder.redirectError(ProcessBuilder.Redirect.INHERIT);
		Process process = builder.start();
		System.err.println("executed.");
		waitForLineOrDie(process, "started", null);
		System.err.println("peer started");
		return process;
	}

	public static String waitForLineOrDie(Process process, String prefix, String input) throws IOException {

		// Write to stream
		if (input != null) {
			PrintWriter pw = new PrintWriter(process.getOutputStream());
			pw.println(input);
			pw.flush();
		}
		String retVal = read(process.getInputStream(), prefix);
		if(retVal == null) {
			process.destroy();
		}
		return retVal;
	}
	
	public static String read(InputStream is, String prefix) throws IOException {
		// Read out dir output
		InputStreamReader isr = new InputStreamReader(is);
		BufferedReader br = new BufferedReader(isr);
		String line;
		
		while ((line = br.readLine()) != null) {
			if (line.trim().startsWith(prefix)) {
				String retVal = line.substring(prefix.length()).trim(); 
				System.err.println("found: "+prefix+" -> "+retVal);
				return retVal;
			}
		}
		System.err.println("no inputstream");
		return null;
	}

	public static int killPeer(Process process) throws InterruptedException, IOException {
		process.destroy();
		process.getErrorStream().close();
		process.getInputStream().close();
		process.getOutputStream().close();
		return process.waitFor();
	}
	
	/**
	 * As set in: tomp2p/nat/src/test/resources/nat-net.sh
	 */
	public static Peer createRealNode(Number160 relayPeerId, String iface) throws Exception {
		// relay
		Bindings b2 = new Bindings();
		b2.addInterface(iface);
		return new PeerBuilder(relayPeerId).ports(5002).bindings(b2).start();
	}
}
