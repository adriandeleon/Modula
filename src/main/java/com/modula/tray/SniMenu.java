package com.modula.tray;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.freedesktop.dbus.annotations.DBusInterfaceName;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.interfaces.DBusInterface;
import org.freedesktop.dbus.interfaces.Properties;
import org.freedesktop.dbus.messages.DBusSignal;
import org.freedesktop.dbus.types.UInt32;
import org.freedesktop.dbus.types.Variant;

/**
 * The exported com.canonical.dbusmenu object backing the SNI tray menu — the protocol
 * KDE and GNOME's AppIndicator extension pull menus through. Layout: a disabled live
 * status line, "Open Nux", a separator, "Quit Nux" (see {@link SniMenuModel}).
 */
@DBusInterfaceName("com.canonical.dbusmenu")
public interface SniMenu extends DBusInterface {

    String PATH = "/MenuBar";

    MenuPair<UInt32, MenuLayout> GetLayout(int parentId, int recursionDepth, List<String> propertyNames);

    List<MenuItemEntry> GetGroupProperties(List<Integer> ids, List<String> propertyNames);

    Variant<?> GetProperty(int id, String name);

    void Event(int id, String eventId, Variant<?> data, UInt32 timestamp);

    List<Integer> EventGroup(List<MenuEventEntry> events);

    boolean AboutToShow(int id);

    MenuPair<List<Integer>, List<Integer>> AboutToShowGroup(List<Integer> ids);

    class LayoutUpdated extends DBusSignal {
        private final UInt32 revision;
        private final int parent;

        public LayoutUpdated(String path, UInt32 revision, int parent) throws DBusException {
            super(path, revision, parent);
            this.revision = revision;
            this.parent = parent;
        }

        public UInt32 getRevision() {
            return revision;
        }

        public int getParent() {
            return parent;
        }
    }

    /** The exported implementation; thread-safe (dbus calls arrive on dbus-java's threads). */
    final class Impl implements SniMenu, Properties {

        private final Runnable onOpen;
        private final Runnable onQuit;
        private volatile Runnable onListen = () -> {};
        private volatile Runnable onRecord = () -> {};
        private volatile boolean listening;
        private volatile boolean recording;
        private final AtomicInteger revision = new AtomicInteger(1);
        private volatile String statusLine = "Not connected";

        public void setOnListen(Runnable onListen) {
            this.onListen = onListen == null ? () -> {} : onListen;
        }

        public void setOnRecord(Runnable onRecord) {
            this.onRecord = onRecord == null ? () -> {} : onRecord;
        }

        Impl(Runnable onOpen, Runnable onQuit) {
            this.onOpen = onOpen;
            this.onQuit = onQuit;
        }

        /**
         * Returns the new revision when anything the menu shows changed, else -1.
         *
         * <p>The listening and recording flags are part of that: they decide two of the labels, and
         * they used never to reach here at all — every read called the two-argument {@code propsFor},
         * whose default left the item permanently reading "Listen" even while playing.
         */
        int setState(String line, boolean nowListening, boolean nowRecording) {
            if (statusLine.equals(line) && listening == nowListening && recording == nowRecording) {
                return -1;
            }
            statusLine = line;
            listening = nowListening;
            recording = nowRecording;
            return revision.incrementAndGet();
        }

        int revision() {
            return revision.get();
        }

        @Override
        public String getObjectPath() {
            return PATH;
        }

        @Override
        public MenuPair<UInt32, MenuLayout> GetLayout(int parentId, int recursionDepth, List<String> propertyNames) {
            MenuLayout layout = parentId == SniMenuModel.ROOT
                    ? SniMenuModel.layout(statusLine, listening, recording)
                    : SniMenuModel.item(parentId, statusLine, listening, recording);
            return new MenuPair<>(new UInt32(revision.get()), layout);
        }

        @Override
        public List<MenuItemEntry> GetGroupProperties(List<Integer> ids, List<String> propertyNames) {
            List<Integer> wanted = ids == null || ids.isEmpty() ? SniMenuModel.ITEM_IDS : ids;
            return wanted.stream()
                    .map(id -> new MenuItemEntry(id, SniMenuModel.propsFor(id, statusLine, listening, recording)))
                    .toList();
        }

        @Override
        public Variant<?> GetProperty(int id, String name) {
            Variant<?> value =
                    SniMenuModel.propsFor(id, statusLine, listening, recording).get(name);
            return value != null ? value : new Variant<>("");
        }

        @Override
        public void Event(int id, String eventId, Variant<?> data, UInt32 timestamp) {
            if (!"clicked".equals(eventId)) {
                return;
            }
            if (id == SniMenuModel.OPEN || id == SniMenuModel.STATUS) {
                onOpen.run();
            } else if (id == SniMenuModel.LISTEN) {
                onListen.run();
            } else if (id == SniMenuModel.RECORD) {
                onRecord.run();
            } else if (id == SniMenuModel.QUIT) {
                onQuit.run();
            }
        }

        @Override
        public List<Integer> EventGroup(List<MenuEventEntry> events) {
            if (events != null) {
                for (MenuEventEntry e : events) {
                    Event(e.id, e.eventId, e.data, e.timestamp);
                }
            }
            return List.of();
        }

        @Override
        public boolean AboutToShow(int id) {
            return false;
        }

        @Override
        public MenuPair<List<Integer>, List<Integer>> AboutToShowGroup(List<Integer> ids) {
            return new MenuPair<>(List.of(), List.of());
        }

        @SuppressWarnings("unchecked")
        @Override
        public <A> A Get(String interfaceName, String propertyName) {
            return (A) GetAll(interfaceName).get(propertyName);
        }

        @Override
        public <A> void Set(String interfaceName, String propertyName, A value) {}

        @Override
        public Map<String, Variant<?>> GetAll(String interfaceName) {
            return Map.of(
                    "Version", new Variant<>(new UInt32(3)),
                    "Status", new Variant<>("normal"),
                    "TextDirection", new Variant<>("ltr"),
                    "IconThemePath", new Variant<>(List.<String>of(), "as"));
        }
    }
}
