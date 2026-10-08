import android.app.UiAutomation;
import android.graphics.Rect;
import android.os.HandlerThread;
import android.view.accessibility.AccessibilityNodeInfo;
import org.xmlpull.v1.XmlSerializer;

/** Shell-only snapshot for our disposable emulator; does not require accessibility idle. */
public final class UiSnapshot {
    public static void main(String[] args) throws Exception {
        HandlerThread thread = new HandlerThread("snapshot");
        thread.start();
        UiAutomation ui = null;
        try {
            Object connection = Class.forName("android.app.UiAutomationConnection")
                    .getConstructor().newInstance();
            ui = (UiAutomation) UiAutomation.class.getConstructor(android.os.Looper.class,
                    Class.forName("android.app.IUiAutomationConnection"))
                    .newInstance(thread.getLooper(), connection);
            UiAutomation.class.getMethod("connect").invoke(ui);
            AccessibilityNodeInfo root = null;
            for (int attempt = 0; root == null && attempt < 10; attempt++) {
                root = ui.getRootInActiveWindow();
                if (root == null) Thread.sleep(100);
            }
            if (root == null) throw new IllegalStateException("No active window");
            XmlSerializer xml = android.util.Xml.newSerializer();
            xml.setOutput(System.out, "UTF-8");
            xml.startDocument("UTF-8", true);
            xml.startTag(null, "hierarchy");
            node(xml, root);
            xml.endTag(null, "hierarchy");
            xml.endDocument();
            xml.flush();
        } finally {
            if (ui != null) UiAutomation.class.getMethod("disconnect").invoke(ui);
            thread.quitSafely();
        }
    }

    private static void node(XmlSerializer xml, AccessibilityNodeInfo node) throws Exception {
        try {
            if (!node.isVisibleToUser()) return;
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            xml.startTag(null, "node");
            xml.attribute(null, "text", value(node.getText()));
            xml.attribute(null, "content-desc", value(node.getContentDescription()));
            xml.attribute(null, "class", value(node.getClassName()));
            xml.attribute(null, "package", value(node.getPackageName()));
            xml.attribute(null, "bounds", bounds.toShortString());
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) node(xml, child);
            }
            xml.endTag(null, "node");
        } finally { node.recycle(); }
    }

    private static String value(CharSequence text) { return text == null ? "" : text.toString(); }
}
