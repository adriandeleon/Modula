package com.modula.tray;

import java.util.Map;

import org.freedesktop.dbus.types.Variant;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SniMenuModelTest {

    private static Object prop(int id, String name, boolean listening, boolean recording) {
        Map<String, Variant<?>> props = SniMenuModel.propsFor(id, "status", listening, recording);
        Variant<?> value = props.get(name);
        return value == null ? null : value.getValue();
    }

    /**
     * The item said "Listen" while the radio was playing, because every read went through the
     * two-argument overload whose default is "not listening" and the real flag never reached here.
     */
    @Test
    void theListenItemFollowsTheReceiver() {
        assertEquals("Listen", prop(SniMenuModel.LISTEN, "label", false, false));
        assertEquals("Stop", prop(SniMenuModel.LISTEN, "label", true, false));
    }

    @Test
    void theRecordItemFollowsTheRecording() {
        assertEquals("Record", prop(SniMenuModel.RECORD, "label", true, false));
        assertEquals("Stop recording", prop(SniMenuModel.RECORD, "label", true, true));
    }

    /** Recording tees the audio, so there is nothing to record until the receiver is running. */
    @Test
    void recordingIsDisabledUntilThereIsSomethingToRecord() {
        assertEquals(false, prop(SniMenuModel.RECORD, "enabled", false, false));
        assertEquals(true, prop(SniMenuModel.RECORD, "enabled", true, false));
    }

    @Test
    void theStatusLineIsShownAndNotClickable() {
        assertEquals("status", prop(SniMenuModel.STATUS, "label", true, false));
        assertEquals(false, prop(SniMenuModel.STATUS, "enabled", true, false));
    }

    /** Ids are wire identifiers: a duplicate silently merges two items into one. */
    @Test
    void everyItemHasItsOwnIdAndAppearsInTheLayout() {
        assertEquals(
                SniMenuModel.ITEM_IDS.size(),
                SniMenuModel.ITEM_IDS.stream().distinct().count());
        assertFalse(SniMenuModel.ITEM_IDS.contains(SniMenuModel.ROOT));
        assertEquals(
                SniMenuModel.ITEM_IDS.size(),
                SniMenuModel.layout("status", true, true).children.size());
    }

    /** Every item must carry something renderable, or the host draws a blank row. */
    @Test
    void everyItemHasALabelOrIsASeparator() {
        for (int id : SniMenuModel.ITEM_IDS) {
            Map<String, Variant<?>> props = SniMenuModel.propsFor(id, "status", true, true);
            boolean separator = "separator"
                    .equals(props.containsKey("type") ? props.get("type").getValue() : null);
            assertTrue(separator || props.containsKey("label"), "item " + id + " has neither label nor type");
            if (props.containsKey("label")) {
                assertNotNull(props.get("label").getValue());
                assertFalse(props.get("label").getValue().toString().isBlank());
            }
        }
    }
}
