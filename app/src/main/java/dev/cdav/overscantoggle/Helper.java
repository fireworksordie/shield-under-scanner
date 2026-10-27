package dev.cdav.overscantoggle;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;

/**
 * Runs as the shell user (launched through Shizuku with app_process).
 * Talks to NVIDIA's INvDisplay HIDL service the same way OverscanComp.apk does.
 * Line protocol on stdin/stdout. Replies start with "@":  G -> @VAL <tenths>,  S <tenths> -> @OK,  errors -> @ERR <why>
 * All Android classes are reached by reflection so this compiles without the hidden APIs.
 */
public class Helper {
    private static final String DESC = "vendor.nvidia.hardware.graphics.display@1.0::INvDisplay";
    private static final int DISPLAY = 3;       // same display id OverscanComp uses
    private static final int HWC_PROP = 5;      // same property id OverscanComp uses

    private static Class<?> hp;
    private static Class<?> ihb;
    private static Object svc;

    private static void init() throws Exception {
        if (svc != null) return;
        hp = Class.forName("android.os.HwParcel");
        ihb = Class.forName("android.os.IHwBinder");
        Object s = Class.forName("android.os.HwBinder")
                .getMethod("getService", String.class, String.class)
                .invoke(null, DESC, "default");
        if (s == null) throw new IllegalStateException("INvDisplay service not found");
        svc = s;
    }

    private static Object parcel() throws Exception {
        return hp.getConstructor().newInstance();
    }

    private static void put(Object p, String method, Class<?> type, Object v) throws Exception {
        hp.getMethod(method, type).invoke(p, v);
    }

    private static Object transact(int code, Object req) throws Exception {
        Object rep = parcel();
        ihb.getMethod("transact", int.class, hp, hp, int.class).invoke(svc, code, req, rep, 0);
        hp.getMethod("verifySuccess").invoke(rep);
        hp.getMethod("releaseTemporaryStorage").invoke(req);
        return rep;
    }

    private static float getUnderscan() throws Exception {
        Object req = parcel();
        put(req, "writeInterfaceToken", String.class, DESC);
        put(req, "writeInt32", int.class, DISPLAY);
        Object rep = transact(18, req);
        float f = (Float) hp.getMethod("readFloat").invoke(rep);
        hp.getMethod("release").invoke(rep);
        return f;
    }

    private static void setUnderscan(float f) throws Exception {
        Object req = parcel();
        put(req, "writeInterfaceToken", String.class, DESC);
        put(req, "writeInt32", int.class, DISPLAY);
        put(req, "writeFloat", float.class, f);
        Object rep = transact(19, req);
        hp.getMethod("release").invoke(rep);
    }

    private static void setHwcProperty(String value) throws Exception {
        Object req = parcel();
        put(req, "writeInterfaceToken", String.class, DESC);
        put(req, "writeInt32", int.class, HWC_PROP);
        put(req, "writeString", String.class, value);
        Object rep = transact(29, req);
        hp.getMethod("release").invoke(rep);
    }

    private static String handle(String line) throws Exception {
        if (line.equals("G")) {
            return "@VAL " + Math.round(getUnderscan() * 1000f);
        }
        if (line.startsWith("S ")) {
            int tenths = Math.max(0, Math.min(300, Integer.parseInt(line.substring(2).trim())));
            float f = tenths / 1000f;
            setUnderscan(f);
            setHwcProperty(Float.toString(f));
            return "@OK";
        }
        if (line.equals("Q")) {
            System.exit(0);
        }
        return "@ERR unknown command: " + line;
    }

    public static void main(String[] args) throws Exception {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        String line;
        while ((line = in.readLine()) != null) {
            String reply;
            try {
                init();
                reply = handle(line.trim());
            } catch (Throwable t) {
                Throwable c = (t instanceof InvocationTargetException && t.getCause() != null) ? t.getCause() : t;
                reply = "@ERR " + c;
            }
            System.out.println(reply);
            System.out.flush();
        }
    }
}
