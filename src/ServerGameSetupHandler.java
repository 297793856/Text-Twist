import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ServerGameSetupHandler extends Server implements Runnable {
	private static final int NTHREADS = 10;
	static final int SERVER_INVITE_SEND = 0x100, SERVER_INVITE_ACCEPT = 0x101, SERVER_INVITE_ERROR = 0x102,
			SERVER_INVITE_EXPIRED = 0x103, SERVER_INVITE_ALL_READY = 0x104;
	static final int SERVER_SCOREBOARD = 0x110;

	private List<IClient> loggedUsers;
	private List<Invite> pendingInvites;

	// { invite, # users to wait }
	private Map<Invite, Integer> acceptedInvites;

	private String dictFile;

	private static Map<Match, ServerMatchHandler> matchHandlers = new TreeMap<>();

	ServerGameSetupHandler(List<IClient> u, List<Invite> i) {
		this.loggedUsers = u;
		this.pendingInvites = i;
		this.acceptedInvites = new TreeMap<>();
		this.dictFile = Server.DICTIONARY_PATH;
	}

	public void run() {
		Thread.currentThread().setName("ServerGSH");
		ExecutorService e = Executors.newFixedThreadPool(NTHREADS);
		try (ServerSocket ssocket = new ServerSocket(Server.TCP_PORT)) {
			System.out.println("[SERVERGSH] Waiting for clients");
			while (true) {
				Socket csocket = ssocket.accept();
				Handler h = new Handler(csocket);
				e.submit(h);
			}
		} catch (IOException exec) {
		} finally {
			e.shutdown();
		}
	}

	private class Handler implements Runnable {
		private static final long RESPONSE_WAITING_MSEC = 1 * 1000;
		private static final int SOCKET_TIMEOUT = 2 * 100;
		private static final int TIME_WAIT_FOR_CLIENT_SEC = 10;
		Socket socket;

		Handler(Socket s) {
			this.socket = s;
		}

		@Override
		public void run() {
			Thread.currentThread().setName("ServerGSHH");
			int action;
			try (ObjectInputStream in = new ObjectInputStream(socket.getInputStream());
					ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream())) {
				this.socket.setSoTimeout(SOCKET_TIMEOUT);
				LocalDateTime ex = LocalDateTime.now();
				boolean received = false;
				while (connectingWithClient(ex) && !received) {
					try {
						action = (Integer) in.readInt();
						switch (action) {
						case SERVER_INVITE_SEND:
							invite_send(in, out);
							break;
						case SERVER_INVITE_ACCEPT:
							invite_accept(in, out);
							break;
						case SERVER_SCOREBOARD:
							send_scores(in, out);
							break;
						default:
							System.err.println("Unrecognized action: " + action);
							break;
						}
						received = true;
					} catch (SocketTimeoutException e) {
					}
				}

			} catch (IOException | ClassNotFoundException | InterruptedException e) {
				e.printStackTrace();
			} finally {

			}
		}

		private void send_scores(ObjectInputStream in, ObjectOutputStream out)
				throws IOException, ClassNotFoundException, InterruptedException {
			// no need to synchronize on the file here too
			Map<String, String> scores = Utils.ParseFile(SCORES_DB_PATH);
			System.out.println("[SERVERGSH] Sending scores..");

			/*
			 * PROTOCOL: out[ACTION: get scores] -> in[success] -> in[scores]
			 */
			if (scores != null) {
				// reordering scores ?
				out.writeInt(SERVER_SCOREBOARD);
				out.writeObject(scores);
			} else {
				out.writeInt(SERVER_SCOREBOARD - 1);
			}
			out.flush();
		}

		private void invite_accept(ObjectInputStream in, ObjectOutputStream out)
				throws ClassNotFoundException, IOException, InterruptedException {
			/*
			 * PROTOCOL: out[ACTION: accept invites] -> out[from] -> out[invite]
			 * in[game ready] -> in[letters]
			 */
			LocalDateTime exp = LocalDateTime.now();
			String from = null;
			Invite invite = null;
			boolean received = false, disconnect = false;
			while (connectingWithClient(exp) && !received) {
				try {
					from = (String) in.readObject();
					System.out.println("[SERVERGSH] Accepted invite from " + from);

					invite = (Invite) in.readObject();
					if (!Invite.valid(invite))
						invite = null;
					received = true;
				} catch (SocketTimeoutException e) {
				}
			}
			if (invite == null || from == null) {
				out.writeInt(SERVER_INVITE_ERROR);
				return;
			}
			int nPlayers = invite.to().size();
			boolean returnResponse = false, hasExpired = false;
			// checking the invite is valid
			if (!pendingInvites.contains(invite)) {
				out.writeInt(SERVER_INVITE_ERROR);
				return;
			}
			synchronized (acceptedInvites) {
				// if someone else has already accepted this invite, it's in the
				// dict()
				if (acceptedInvites.containsKey(invite)) {
					int nAcceptedInvites = acceptedInvites.get(invite) - 1;
					acceptedInvites.put(invite, nAcceptedInvites);
				} else {
					--nPlayers; // -1: this player has accepted
					acceptedInvites.put(invite, nPlayers);
				}
			}

			while (!returnResponse) {
				if (hasExpired = hasInviteExpired(invite))
					returnResponse = true;
				else
					synchronized (acceptedInvites) {
						// wait for everyone
						returnResponse = acceptedInvites.get(invite) == 0;
					}

				if (!returnResponse)
					Thread.sleep(RESPONSE_WAITING_MSEC);
			}

			// the invite is now invalid
			pendingInvites.remove(invite);

			// everybody still online?
			String online = null;
			for (IClient c : invite.to())
				try {
					if (from.equals(c.getName()))
						// my client is still online
						online = from;
				} catch (Exception e) {
					// continue looping to see whether my client is on
					disconnect = true;
				}

			// no point in sending the error back to an offline user
			if (disconnect && online == null)
				;
			else if (hasExpired || disconnect)
				out.writeInt(SERVER_INVITE_EXPIRED);
			else {
				String letters;
				// the match can start
				// get the match
				Match m = Match.getMatch(invite);
				ExecutorService ex = Executors.newSingleThreadExecutor();

				// initialise the match
				for (IClient c : invite.to())
					m.setScore(c.getName(), 0);

				// get and set the letters
				do {
					letters = Utils.AtLine(dictFile, Math.abs(new Random().nextLong() % Utils.FileLines(dictFile)));
				} while (letters.length() < MIN_LETTERS);
				m.setLetters(letters);

				out.writeInt(SERVER_INVITE_ALL_READY);
				out.writeObject(m.getLetters());

				ServerMatchHandler mh = null;
				synchronized (matchHandlers) {
					if (!matchHandlers.containsKey(m)) {
						mh = ServerMatchHandler.getMatchHandler(m);
						matchHandlers.put(m, mh);
					}
				}

				// if not already started (first!)
				if (mh != null)
					ex.submit(mh);
			}
		}

		private void invite_send(ObjectInputStream in, ObjectOutputStream out)
				throws IOException, ClassNotFoundException, InterruptedException {
			/*
			 * PROTOCOL: out[ACTION: send invites] -> out[from] -> out[names]
			 * in[success]
			 */
			LocalDateTime now = LocalDateTime.now();
			int n = 0;
			boolean success = false;
			ArrayList<String> names = new ArrayList<>();
			ArrayList<IClient> toInvite = new ArrayList<>();
			String from = null;
			boolean received = false;

			while (connectingWithClient(now) && !received) {
				try {
					from = (String) in.readObject();
					System.out.println("[SERVERGSH] Forwarding invite from " + from);

					for (String s : (String[]) in.readObject())
						names.add(s.trim());

					received = true;
				} catch (SocketTimeoutException e) {
				}
			}
			n = names.size();
			// will need to call from's callback; adding the user him/herself to
			// the list of players
			if (!names.contains(from) && n > 0) {
				names.add(from);
				++n;
			}

			if (n < MIN_PLAYERS_PER_MATCH) { // min 2 players
				out.writeInt(SERVER_INVITE_ERROR);
				return;
			}
			try {
				synchronized (loggedUsers) {
					// checking whether everyone's online
					for (IClient c : loggedUsers) {
						try {
							if (names.contains(c.getName())) {
								--n;
								toInvite.add(c);
							}
						} catch (Exception e) {
						}
					}

					success = n == 0;

				}
				if (!success) {
					out.writeInt(SERVER_INVITE_ERROR);
					return;
				}

				Invite invite = new Invite(from, toInvite, now);
				// sending the invite to everyone
				for (IClient c : toInvite)
					c.addInvite(invite);

				pendingInvites.add(invite);

				// and finally sending the success code
				out.writeInt(SERVER_INVITE_SEND);
			} finally {
				out.flush();
			}
		}

		private boolean hasInviteExpired(Invite invite) {
			return invite.time().isBefore(LocalDateTime.now().minusMinutes(MAX_MINUTES_PENDING_INVITE));
		}

		private boolean connectingWithClient(LocalDateTime ex) {
			return LocalDateTime.now().minusSeconds(TIME_WAIT_FOR_CLIENT_SEC).isBefore(ex);
		}
	}
}
