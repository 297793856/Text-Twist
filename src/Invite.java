import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/*
 * conditionally thread safe
 */
public class Invite implements Serializable, Comparable<Invite> {
	private static final long serialVersionUID = 1L;
	private String from;
	private List<IClient> to;
	private LocalDateTime time;
	private int _id;
	private static Integer id = 0;

	/**
	 * 
	 * @param from sender's username; can't be null
	 * @param to List<> of receivers' usernames; can't be null
	 * @param sent time the invite is open; can't be null
	 */
	public Invite(String from, List<IClient> to, LocalDateTime sent) {
		this.from = from;
		this.to = to;
		this.time = sent;
		assert (valid(this));
		synchronized (Invite.id) {
			Invite.id = (Invite.id + 1) % Integer.MAX_VALUE;
			this._id = id;
		}
	}

	/**
	 * 
	 * @return a unique id for this invite
	 */
	public int id() {
		return _id;
	}

	/**
	 * 
	 * @return the sender's username
	 */
	public String from() {
		return from;
	}

	/**
	 * 
	 * @return a List<> of IClients this invite was sent to
	 */
	public List<IClient> to() {
		return to;
	}

	/**
	 * 
	 * @return the time from when this invite is open
	 */
	public LocalDateTime time() {
		return time;
	}

	@Override
	public boolean equals(Object o) {
		if (!(o instanceof Invite))
			return false;
		Invite i = (Invite) o;
		return i.id() == this.id();
	}

	@Override
	public int compareTo(Invite i) {
		return this.id() - i.id();
	}

	/**
	 * 
	 * @return a byte representation for this
	 */
	public byte[] toBytes() {
		try (ByteArrayOutputStream bs = new ByteArrayOutputStream();
				ObjectOutputStream o = new ObjectOutputStream(bs);) {
			o.writeObject(this);
			return bs.toByteArray();
		} catch (IOException e) {
			e.printStackTrace();
			return null;
		}
	}

	/**
	 * 
	 * @param bytes bytes representing an Invite object
	 * @return an instance of an Invite represented by the bytes in bytes
	 */
	public static Invite fromBytes(byte[] bytes) {
		try (ByteArrayInputStream bs = new ByteArrayInputStream(bytes);
				ObjectInputStream i = new ObjectInputStream(bs);) {
			Invite invite = (Invite) i.readObject();
			assert (valid(invite));
			return invite;
		} catch (IOException | ClassNotFoundException e) {
			e.printStackTrace();
			return null;
		}
	}

	/**
	 * 
	 * @param invite an Invite object
	 * @return true iff invite represents an Invite object
	 */
	public static boolean valid(Invite invite) {
		return invite.from != null && invite.time != null && invite.to != null;
	}
}
