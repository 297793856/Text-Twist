import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ServerMatchHandler extends Server implements Runnable, Comparable<ServerMatchHandler> {

	private String id_prefix;
	private Match match;
	private int port;
	private static Map<Match, ServerMatchHandler> matchHandlers = new TreeMap<>();
	private static Set<String> dict = getDict();
	private String scores_db_path;

	private ServerMatchHandler(Match m) {
		this.match = m;
		this.port = Server.UDP_PORT + m.invite().id();
		this.id_prefix = Server.ID_PREFIX;
		this.scores_db_path = Server.SCORES_DB_PATH;
	}

	public static ServerMatchHandler getMatchHandler(Match m) {
		ServerMatchHandler mh = null;
		synchronized (matchHandlers) {
			if (matchHandlers.containsKey(m))
				mh = matchHandlers.get(m);
			else
				mh = new ServerMatchHandler(m);
		}
		return mh;
	}

	@Override
	public void run() {
		Thread.currentThread().setName("ServerMH " + match.id());
		ExecutorService ex = Executors.newSingleThreadExecutor();
		try {
			// get words received by the users
			Map<String, List<String>> result = ex
					.submit(new SRUDP_Server(port, match.players(), id_prefix, 60 * TIME_OPEN_GAME_MIN)).get();

			// update scores
			for (Map.Entry<String, List<String>> e : result.entrySet()) {
				// if the user is playing this match
				if (match.playersNames().contains(e.getKey()))
					for (String s : e.getValue())
						if (isWordValid(s.toLowerCase(), match.getLetters())) {
							updateScore(e.getKey(), s.length());
							match.addScore(e.getKey(), s.length());
						}
			}

			// broadcast scores
			InetAddress mcGroup = InetAddress.getByName(Server.SERVER_MULTICAST);
			DatagramPacket packet;
			String score;

			try (MulticastSocket sk = new MulticastSocket(Server.SERVER_MULTICAST_PORT);) {
				sk.setTimeToLive(1);
				sk.setLoopbackMode(false);
				sk.setReuseAddress(true);

				byte[] bytes;
				for (Entry<String, Integer> e : match.getScores().entrySet()) {
					score = "" + match.id() + " Player: [" + e.getKey() + "] scored: " + e.getValue() + " points";
					bytes = score.getBytes();
					packet = new DatagramPacket(bytes, bytes.length, mcGroup, Server.SERVER_MULTICAST_PORT);
					sk.send(packet);
				}
				// the match is over
				synchronized (matchHandlers) {
					matchHandlers.remove(match);
				}
				Match.end(match);
			} finally {
				
			}
		} catch (InterruptedException e) {
			e.printStackTrace();
		} catch (ExecutionException e) {
			e.printStackTrace();
		} catch (UnknownHostException e1) {
			e1.printStackTrace();
		} catch (IOException e1) {
			e1.printStackTrace();
		} finally {
			ex.shutdown();
		}
	}

	private void updateScore(String user, int toAdd) {
		String oldvalue = Utils.FindInFile(this.scores_db_path, user);
		if (oldvalue.equals(""))
			// new entry
			Utils.WriteToFile(this.scores_db_path, new Pair<String, String>(user, "" + toAdd));
		else {
			// update the user's score
			try {
				int n = Integer.parseInt(oldvalue);
				Utils.UpdateInFile(this.scores_db_path, user, oldvalue, "" + (toAdd + n));
			} catch (NumberFormatException e) {
				e.printStackTrace();
			}
		}
	}

	@Override
	public int compareTo(ServerMatchHandler s) {
		return this.match.compareTo(s.match);
	}

	private static synchronized Set<String> getDict() {
		Path path = Paths.get(Server.DICTIONARY_PATH);
		String d = null;
		try {
			d = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
		} catch (IOException e) {
			e.printStackTrace();
			return null;
		}
		Set<String> set = new HashSet<String>();
		for (String s : d.split(System.lineSeparator()))
			set.add(s);
		return set;
	}

	private boolean isWordValid(String s, String charSet) {
		boolean isInDictionary = s != null && dict.contains(s);
		// [charSet]* : any letter in charSet (n>=0)
		return isInDictionary && charSet != null && s.toLowerCase().matches("[" + charSet.toLowerCase() + "]*");
	}

}
