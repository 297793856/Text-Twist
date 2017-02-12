import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.rmi.RemoteException;
import java.rmi.server.RemoteObject;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

public class ClientRemote extends RemoteObject implements IClient, Comparable<IClient> {

	private static final long serialVersionUID = 1L;

	private String name;
	private String password;

	private long id;
	private boolean notify;
	private Cookie sCookie;

	private ArrayList<Invite> pendingInvites;

	public ClientRemote(String name, String pw, boolean notify) {
		this.name = name;
		setPassw(pw);
		this.pendingInvites = new ArrayList<>();
		this.id = 0;
		this.notify = notify;
	}

	public void setName(String n) {
		this.name = n;
	}

	@Override
	public String getName() throws RemoteException {
		return name;
	}

	private void setPassw(String pw) {
		// never in clear
		try {
			MessageDigest md = MessageDigest.getInstance("MD5");
			md.update(pw.getBytes("UTF-8"));
			md.update(("" + id).getBytes("UTF-8")); // salt

			this.password = new BigInteger(1, md.digest()).toString(16);
		} catch (NoSuchAlgorithmException e) {
			e.printStackTrace();
		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
		}
	}

	@Override
	public String getPassw() throws RemoteException {
		return this.password;
	}

	@Override
	public long getId() throws RemoteException {
		return id;
	}

	@Override
	public void setId(long v) throws RemoteException {
		this.id = v;

	}

	@Override
	public void addInvite(Invite invite) throws RemoteException {
		synchronized (this.pendingInvites) {
			this.pendingInvites.add(invite);
		}
		if (notify && !invite.from().equals(name))
			System.out.println("[*] New invite from: " + invite.from());
	}

	@Override
	public List<Invite> getInvites() throws RemoteException {
		return this.pendingInvites;
	}

	@Override
	public void setCookie(Cookie c) throws RemoteException {
		this.sCookie = new Cookie(c);
	}

	@Override
	public Cookie getCookie() throws RemoteException {
		return new Cookie(this.sCookie);
	}

	@Override
	public String toString() {
		return this.name;
	}

	@Override
	public boolean eq(Object o) throws RemoteException {
		if (!(o instanceof IClient))
			return false;

		IClient c = (IClient) o;
		return c.getId() == this.id && c.getName().equals(this.name);
	}

	@Override
	public int compareTo(IClient c) {
		try {
			return (int) (c == null ? 0 : this.name.compareTo(c.getName()));
		} catch (RemoteException e) {
			return 0;
		}
	}

}
