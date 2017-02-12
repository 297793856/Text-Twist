import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/*
 * immutable
 */
public class SRUDP {
	protected int port;
	protected String server_name;

	protected SRUDP(String server, int port) {
		assert (port > 0 && port <= 65535);
		this.port = port;
		this.server_name = server;
	}
	
	public static void main(String[] arg) throws InterruptedException, ExecutionException {
		List<String> words = new ArrayList<>();
		ExecutorService ex = Executors.newFixedThreadPool(20);
		String prefix = "_";
		String server = "localhost";
		int i, j;

		for (i = 0; i != 5; ++i)
			words.add("abcde" + i);

		// round 1
		int nClients = 3;
		SRUDP_Server ss = new SRUDP_Server(10000, nClients, prefix, 0);

		ex.submit(ss);
		Thread.sleep(1000);
		for (i = 0; i != nClients; ++i)
			ex.submit(new SRUDP_Client(server, 10000, "client" + i, words, prefix));

		// round 2
		SRUDP_Server ss2 = new SRUDP_Server(10001, nClients, prefix, 0);

		ex.submit(ss2);
		for (i = 0; i != nClients; ++i)
			ex.submit(new SRUDP_Client(server, 10001, "client" + (i + nClients), words, prefix));

		// final round
		nClients = 100;
		for (i = 0; i != nClients; ++i) {
			ex.submit(new SRUDP_Server(10002 + i, nClients, prefix, 0));
			for (j = 0; j != nClients; ++j)
				ex.submit(new SRUDP_Client(server, 10002 + i, "client" + (j + nClients), words, prefix));

		}
		Thread.sleep(1000);
		
		ex.shutdown();
	}
}
