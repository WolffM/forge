package forge.gamemodes.net.event;

public class LoginEvent implements NetEvent {
    private static final long serialVersionUID = -8865183377417377938L;

    private final String username;
    private final int avatarIndex, sleeveIndex;
    private final String version;
    private final boolean libgdx;
    /** What a server that admits only its own clients asks for ({@code FServerManager#requireCredential}); else {@code null}. */
    private final String credential;
    public LoginEvent(final String username, final int avatarIndex, final int sleeveIndex, final String version, final boolean libgdx) {
        this(username, avatarIndex, sleeveIndex, version, libgdx, null);
    }
    public LoginEvent(final String username, final int avatarIndex, final int sleeveIndex, final String version, final boolean libgdx,
            final String credential) {
        this.username = username;
        this.avatarIndex = avatarIndex;
        this.sleeveIndex = sleeveIndex;
        this.version = version;
        this.libgdx = libgdx;
        this.credential = credential;
    }

    public String getUsername() {
        return username;
    }

    public int getAvatarIndex() {
        return avatarIndex;
    }

    public int getSleeveIndex() {
        return sleeveIndex;
    }

    public String getVersion() {
        return version;
    }

    public boolean isLibgdx() {
        return libgdx;
    }

    public String getCredential() {
        return credential;
    }
}
