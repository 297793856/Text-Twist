import java.io.Serializable;
import java.util.UUID;

/*
 * not thread safe
 */
public class Cookie implements Serializable {
	private static final long serialVersionUID = 1L;
	private UUID cookie;

	public Cookie() {
		cookie = UUID.randomUUID();
	}

	public Cookie(Cookie c2) {
		this.cookie = UUID.fromString(c2.cookie.toString());
	}

	@Override
	public boolean equals(Object o) {
		if (!(o instanceof Cookie))
			return false;
		Cookie c2 = (Cookie) o;
		return c2.cookie.equals(this.cookie);
	}
}
