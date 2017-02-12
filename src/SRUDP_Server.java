import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class SRUDP_Server extends SRUDP implements Callable<Map<String, List<String>>> {
	protected static int FIXED_SIZE = 0x100;
	protected static String ID_PREFIX = "_";

	private static final int NTHREAD = 0x10;
	private static final int TIMEOUT = 1 * 100;

	private ExecutorService exec = Executors.newFixedThreadPool(NTHREAD);
	private int nClients;

	protected Set<String> handledClients;
	protected Map<String, List<String>> results;
	protected DatagramSocket server = null;
	protected Lock lock;
	protected Condition cond;
	protected LocalDateTime endTime; // max time willing to exchange data
	protected long timeout_s;

	/**
	 * 
	 * @param port
	 *            listening port
	 * @param nClients
	 *            number of clients to wait
	 * @param id_pref
	 *            clients' prefixes to their names
	 * @param timeout_s
	 *            the server will listen from now till now + timeout_s in
	 *            seconds
	 */
	public SRUDP_Server(int port, int nClients, String id_pref, long timeout_s) {
		super(null, port);
		ID_PREFIX = id_pref;
		this.lock = new ReentrantLock(true);
		try {
			this.server = new DatagramSocket(this.port);
			server.setSoTimeout(TIMEOUT);
		} catch (SocketException e) {
			e.printStackTrace();
			this.server = null;
			throw new RuntimeException(e);
		}
		this.nClients = nClients;
		this.handledClients = new ConcurrentSkipListSet<>();
		this.results = new TreeMap<>();
		this.cond = lock.newCondition();
		this.timeout_s = timeout_s;
		if (timeout_s > 0)
			this.endTime = LocalDateTime.now().plusSeconds(timeout_s);
		else // no timeout
			this.endTime = LocalDateTime.MAX;
	}

	@SuppressWarnings({ "unchecked", "finally" })
	@Override
	public Map<String, List<String>> call() {
		Thread.currentThread().setName("SRUDP Server");
		DatagramPacket request, response;
		boolean done = false, valid;
		Pair<String, String> data, illegalData;
		illegalData = new Pair<String, String>("" + (char) (ID_PREFIX.charAt(0) + 1), "");
		byte[] illegalDataBytes = Pair.ToByteArray(illegalData);
		List<Future<Pair<String, List<String>>>> hResults = new ArrayList<>();
		try {
			request = new DatagramPacket(new byte[FIXED_SIZE], FIXED_SIZE);
			// need to receive the client's ID to pair up
			while (!done) {
				try {
					synchronized (server) {
						server.receive(request);
					}
					data = (Pair<String, String>) Pair.FromByteArray(request.getData());

					valid = data != null && !handledClients.contains(data.v1) && data.v1.startsWith(ID_PREFIX);

				} catch (SocketTimeoutException e) {
					continue;
				} finally {
					done = nClients == 0 || LocalDateTime.now().isAfter(endTime);
				}

				try {
					if (valid) {
						response = new DatagramPacket(request.getData(), request.getLength(),
								request.getSocketAddress());

						synchronized (server) {
							server.send(response);
						}
						handledClients.add(data.v1);

						SRUDP_ServerHandler h = new SRUDP_ServerHandler(data);
						hResults.add(exec.submit(h));
						--nClients;
					} else {
						// different data
						response = new DatagramPacket(illegalDataBytes, illegalDataBytes.length,
								request.getSocketAddress());

						synchronized (server) {
							server.send(response);
						}
					}
					request.setLength(FIXED_SIZE);
				} catch (IOException e) {
					continue;
				}
			}
			lock.lockInterruptibly();
			while (this.handledClients.size() > 0 && !hasExpired()) {
				cond.await(timeout_s, TimeUnit.SECONDS);
			}

			for (Future<Pair<String, List<String>>> f : hResults) {
				if (hasExpired())
					timeout_s = 10;
				Pair<String, List<String>> p = null;
				try {
					p = f.get(timeout_s, TimeUnit.SECONDS);
				} catch (TimeoutException e) {
				}
				if (p != null) {
					String backToNormal = p.v1.substring(ID_PREFIX.length(), p.v1.length());
					results.put(backToNormal, p.v2);
				}
			}
		} catch (SocketException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		} catch (InterruptedException e) {
			e.printStackTrace();
		} catch (ExecutionException e) {
			e.printStackTrace();
		} finally {
			if (((ReentrantLock) lock).isHeldByCurrentThread())
				lock.unlock();
			exec.shutdown();
			return results;
		}
	}

	private boolean hasExpired() {
		return LocalDateTime.now().isAfter(endTime);
	}

	class SRUDP_ServerHandler implements Callable<Pair<String, List<String>>> {
		private String cid;
		private int nMsg;
		private List<String> messages;

		SRUDP_ServerHandler(Pair<String, String> client) {
			this.cid = client.v1;
			this.nMsg = Integer.parseInt(client.v2);
			this.messages = new ArrayList<>();
		}

		// assuming contents are different
		// (otherwise could use Triple<> adding an #id#, like a stop-and-wait /
		// go-back-n ARQ
		// messages consist of <id, data> in a Pair<>
		@SuppressWarnings({ "unchecked", "finally" })
		@Override
		public Pair<String, List<String>> call() {
			Thread.currentThread().setName("SRUDP Server Handler " + cid);
			DatagramPacket request, response;
			boolean done = false;
			Pair<String, String> data, prev = null;
			Pair<String, List<String>> result;
			System.out.println("[SRUDP_Server] Bound to " + this.cid + " # " + this.nMsg);

			result = new Pair<>(this.cid, this.messages);

			try {
				request = new DatagramPacket(new byte[FIXED_SIZE], FIXED_SIZE);
				// need to receive the client's ID to pair up
				if (nMsg > 0)
					while (!done) {
						try {
							done = done || LocalDateTime.now().isAfter(endTime);
							synchronized (server) {
								server.receive(request);
							}
						} catch (SocketTimeoutException e) {
							continue;
						}

						data = (Pair<String, String>) Pair.FromByteArray(request.getData());
						// the client didn't get my ack
						if (!done && prev != null && data != null && data.v1 != null && data.v1.equals(prev.v1)
								&& data.v2 != null && data.v2.equals(prev.v2)) {
							response = new DatagramPacket(request.getData(), request.getLength(),
									request.getSocketAddress());
							synchronized (server) {
								server.send(response);
							}
						}
						// if the client ids correspond and the data is new
						else if (!done && data != null && this.cid.equals(data.v1)
								&& !this.messages.contains(data.v2)) {
							response = new DatagramPacket(request.getData(), request.getLength(),
									request.getSocketAddress());

							synchronized (server) {
								server.send(response);
							}

							done = --nMsg == 0;
							this.messages.add(data.v2);
							prev = data;
							System.out.println("[SRUDP_Server] Received: " + data.v1 + " " + data.v2 + " #" + nMsg);
						} else {
							// different data
							data = new Pair<String, String>("" + (char) (ID_PREFIX.charAt(0) + 1), "");
							byte[] b = Pair.ToByteArray(data);
							response = new DatagramPacket(b, b.length, request.getSocketAddress());

							synchronized (server) {
								server.send(response);
							}
						}
						request.setLength(FIXED_SIZE);
					}

				System.out.println("[SRUDP_Server] " + cid + " handled");
			} catch (SocketException e) {
				e.printStackTrace();
			} catch (IOException e) {
				e.printStackTrace();
			} finally {
				try {
					lock.lockInterruptibly();
				} catch (InterruptedException e) {
					e.printStackTrace();
				}

				if (((ReentrantLock) lock).isHeldByCurrentThread()) {
					cond.signal();
					lock.unlock();
				}
				handledClients.remove(this.cid);
				return result;
			}

		}

	}
}
