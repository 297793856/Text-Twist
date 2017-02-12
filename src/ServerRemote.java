import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.rmi.RemoteException;
import java.rmi.server.RemoteObject;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class ServerRemote extends RemoteObject implements IServer, Serializable {
	private static final long serialVersionUID = 1L;

	private static final int MAX_NAME_LEN = 16;

	public static int MIN_PASSWORD_LENGTH;
	public static int MAX_MINUTES_PENDING_INVITE;

	private static ServerRemote thisServer = null;
	// keep it secret! (overwritten by the value in the config file)
	private static String SALT = "45P!R";

	private static String sUserDB = "";

	// logged in users
	private List<IClient> loggedUsers;
	// invites
	private List<Invite> pendingInvites;

	private ServerRemote(String configFile) {
		Thread.currentThread().setName("ServerRemote");
		try {
			sUserDB = Utils.FindInFile(configFile, "USER_DB_PATH");
			MAX_MINUTES_PENDING_INVITE = Integer.parseInt(Utils.FindInFile(configFile, "MAX_MINUTES_PENDING_INVITE"));
			SALT = Utils.FindInFile(configFile, "SERVER_SALT");
			MIN_PASSWORD_LENGTH = Integer.parseInt(Utils.FindInFile(configFile, "MIN_PASSWORD_LENGTH"));

			assert (MIN_PASSWORD_LENGTH > 0 && SALT.length() > 0 && MAX_MINUTES_PENDING_INVITE > 0
					&& sUserDB.length() > 0);
		} catch (Exception e) {
			e.printStackTrace();
			// can't recover
			return;
		}

		this.loggedUsers = new CopyOnWriteArrayList<>();
		this.pendingInvites = new CopyOnWriteArrayList<>();

		(new Thread(new ServerLogoutHandler(this.loggedUsers))).start();
		(new Thread(new ServerGameSetupHandler(this.loggedUsers, this.pendingInvites))).start();
	}

	public synchronized static ServerRemote getServer(String configFile) {
		ServerRemote s = null;
		if (thisServer == null)
			s = new ServerRemote(configFile);
		else
			s = thisServer;
		return s;
	}

	@Override
	public synchronized int signup(String name, String pw) throws RemoteException {
		if (pw == null)
			return SIGNUP_ERRS.E_PW_NONE;
		if (pw.length() < MIN_PASSWORD_LENGTH)
			return SIGNUP_ERRS.E_PW_LEN;
		if (name == null || name.length() < 1 || name.contains(Utils.SEPARATOR) || !isNameValid(name))
			return SIGNUP_ERRS.E_NAME;
		if (name.length() > Server.STR_MAX_LEN)
			return SIGNUP_ERRS.E_NAME;
		if (Utils.FindInFile(sUserDB, name).length() > 0)
			return SIGNUP_ERRS.E_NAME_TAKEN;

		Utils.WriteToFile(sUserDB, new Pair<>(name, this.getUserHashedPassw(name, pw)));
		System.out.println("[SERVER] Signup from " + name);
		return SIGNUP_ERRS.E_OK;
	}

	@Override
	public synchronized boolean login(String name, String pw, IClient client) throws RemoteException {
		if (name == null || pw == null || client == null)
			return false; // who are you!?
		if (name.contains(Utils.SEPARATOR))
			return false; // messing up the database!
		if (!isNameValid(name))
			return false;

		boolean r = true;
		// if the user is already logged in
		for (IClient c : this.loggedUsers)
			try {
				if (c.eq(client)) {
					r = false;
					break;
				}
			} catch (Exception e) {
			}

		// if the user exists
		r = r && getUserHashedPassw(name, pw).equals(Utils.FindInFile(sUserDB, name));
		if (r)
			this.loggedUsers.add(client);
		System.out.println("[SERVER] Login from " + name + " (" + r + ")");
		return r;
	}

	@Override
	public boolean logout(IClient cIn) throws RemoteException {
		if (cIn == null)
			return false;
		System.out.println("[SERVER] Logout request from " + cIn.getName());
		boolean success = false;

		success = loggedUsers.remove(cIn);

		System.out.println("[SERVER] Logout request " + (success ? "done" : "failed"));

		return success;
	}

	private String getUserHashedPassw(String name, String pw) {
		try {
			MessageDigest md = MessageDigest.getInstance("MD5");
			md.update(pw.getBytes("UTF-8"));
			md.update(name.getBytes("UTF-8")); // salt

			return new BigInteger(1, md.digest()).toString(16);
		} catch (NoSuchAlgorithmException e) {
			e.printStackTrace();
		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
		}
		return null;
	}

	static boolean isNameValid(String name) {
		if (name == null)
			return false;
		boolean valid = true;
		for (int i = 0; i != name.length() && valid; ++i) {
			valid = Character.isLetterOrDigit(name.charAt(i));
		}
		return valid && name.length() < ServerRemote.MAX_NAME_LEN && name.length() > 0;
	}

	private class ServerLogoutHandler implements Runnable {
		private List<IClient> loggedUsers;
		private final int N_WAITING_MSEC = 1 * 1000; // 1s
		private int _count; // lambda

		ServerLogoutHandler(List<IClient> u) {
			this.loggedUsers = u;
		}

		public void run() {
			Thread.currentThread().setName("ServerLH");
			while (true) {
				try {
					_count = 0;
					lookForLogouts();
					Thread.sleep(N_WAITING_MSEC);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
		}

		private void lookForLogouts() {
			// if the user isn't online, the call to getId() will fail
			this.loggedUsers.forEach((client) -> {
				try {
					client.getId();
				} catch (Exception e) {
					this.loggedUsers.remove(client);
					++_count;
				}
			});

			if (_count > 0) {
				System.out.println("[SERVERLH] Logging out " + _count + " user" + (_count != 1 ? 's' : ' '));
			}
		}

	}
}
