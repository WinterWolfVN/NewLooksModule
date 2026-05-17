package de.robv.android.xposed;

import android.util.Log;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;
import de.robv.android.xposed.XC_MethodHook.MethodHookParam;
import de.robv.android.xposed.callbacks.XC_InitPackageResources;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class XposedBridge {
    public static final ClassLoader BOOTCLASSLOADER = ClassLoader.getSystemClassLoader();
    public static final String TAG = "Xposed";
    public static int XPOSED_BRIDGE_VERSION = 100;
    static boolean disableHooks = false;

    private static final Object[] EMPTY_ARRAY = new Object[0];
    private static final Map<Member, CopyOnWriteSortedSet<XC_MethodHook>> sHookedMethodCallbacks = new HashMap<>();
    static final CopyOnWriteSortedSet<XC_LoadPackage> sLoadedPackageCallbacks = new CopyOnWriteSortedSet<>();
    static final CopyOnWriteSortedSet<XC_InitPackageResources> sInitPackageResourcesCallbacks = new CopyOnWriteSortedSet<>();

    private XposedBridge() {}

    public synchronized static void log(String text) {
        Log.i(TAG, text);
    }

    public synchronized static void log(Throwable t) {
        Log.e(TAG, Log.getStackTraceString(t));
    }

    public static XC_MethodHook.Unhook hookMethod(Member hookMethod, XC_MethodHook callback) {
        if (!(hookMethod instanceof Method) && !(hookMethod instanceof Constructor<?>)) {
            throw new IllegalArgumentException("Only methods and constructors can be hooked: " + hookMethod.toString());
        } else if (hookMethod.getDeclaringClass().isInterface()) {
            throw new IllegalArgumentException("Cannot hook interfaces: " + hookMethod.toString());
        } else if (Modifier.isAbstract(hookMethod.getModifiers())) {
            throw new IllegalArgumentException("Cannot hook abstract methods: " + hookMethod.toString());
        }

        CopyOnWriteSortedSet<XC_MethodHook> callbacks;
        synchronized (sHookedMethodCallbacks) {
            callbacks = sHookedMethodCallbacks.get(hookMethod);
            if (callbacks == null) {
                callbacks = new CopyOnWriteSortedSet<>();
                sHookedMethodCallbacks.put(hookMethod, callbacks);
            }
        }
        callbacks.add(callback);
        return callback.new Unhook(hookMethod);
    }

    public static Object handleHookedMethod(Member method, Object additionalInfoObj, Object thisObject, Object[] args) throws Throwable {
        AdditionalHookInfo additionalInfo = (AdditionalHookInfo) additionalInfoObj;
        if (disableHooks) {
            return invokeOriginalMethod(method, thisObject, args);
        }

        Object[] callbacksSnapshot = additionalInfo.callbacks.getSnapshot();
        final int callbacksLength = callbacksSnapshot.length;
        if (callbacksLength == 0) {
            return invokeOriginalMethod(method, thisObject, args);
        }

        MethodHookParam param = new MethodHookParam();
        param.method = method;
        param.thisObject = thisObject;
        param.args = args;

        int beforeIdx = 0;
        do {
            try {
                ((XC_MethodHook) callbacksSnapshot[beforeIdx]).beforeHookedMethod(param);
            } catch (Throwable t) {
                log(t);
                param.setResult(null);
                param.returnEarly = false;
                continue;
            }
            if (param.returnEarly) {
                beforeIdx++;
                break;
            }
        } while (++beforeIdx < callbacksLength);

        if (!param.returnEarly) {
            try {
                param.setResult(invokeOriginalMethod(method, param.thisObject, param.args));
            } catch (InvocationTargetException e) {
                param.setThrowable(e.getCause());
            } catch (Throwable t) {
                param.setThrowable(t);
            }
        }

        int afterIdx = beforeIdx - 1;
        do {
            Object lastResult = param.getResult();
            Throwable lastThrowable = param.getThrowable();
            try {
                ((XC_MethodHook) callbacksSnapshot[afterIdx]).afterHookedMethod(param);
            } catch (Throwable t) {
                log(t);
                if (lastThrowable == null) param.setResult(lastResult);
                else param.setThrowable(lastThrowable);
            }
        } while (--afterIdx >= 0);

        if (param.hasThrowable()) throw param.getThrowable();
        else return param.getResult();
    }

    public static Object invokeOriginalMethod(Member method, Object thisObject, Object[] args)
            throws NullPointerException, IllegalAccessException, IllegalArgumentException, InvocationTargetException {
        if (args == null) args = EMPTY_ARRAY;
        if (method instanceof Method) {
            ((Method) method).setAccessible(true);
            return ((Method) method).invoke(thisObject, args);
        } else if (method instanceof Constructor) {
            ((Constructor<?>) method).setAccessible(true);
            try {
                return ((Constructor<?>) method).newInstance(args);
            } catch (InstantiationException e) {
                throw new RuntimeException(e);
            }
        }
        throw new IllegalArgumentException("Method must be Method or Constructor");
    }

    public static final class CopyOnWriteSortedSet<E> {
        private transient volatile Object[] elements = EMPTY_ARRAY;
        public synchronized boolean add(E e) {
            int index = indexOf(e);
            if (index >= 0) return false;
            Object[] newElements = new Object[elements.length + 1];
            System.arraycopy(elements, 0, newElements, 0, elements.length);
            newElements[elements.length] = e;
            elements = newElements;
            return true;
        }
        public synchronized boolean remove(E e) {
            int index = indexOf(e);
            if (index == -1) return false;
            Object[] newElements = new Object[elements.length - 1];
            System.arraycopy(elements, 0, newElements, 0, index);
            System.arraycopy(elements, index + 1, newElements, index, elements.length - index - 1);
            elements = newElements;
            return true;
        }
        private int indexOf(Object o) {
            for (int i = 0; i < elements.length; i++) {
                if (o.equals(elements[i])) return i;
            }
            return -1;
        }
        public Object[] getSnapshot() {
            return elements;
        }
    }

    public static class AdditionalHookInfo {
        final CopyOnWriteSortedSet<XC_MethodHook> callbacks;
        final Class<?>[] parameterTypes;
        final Class<?> returnType;
        public AdditionalHookInfo(CopyOnWriteSortedSet<XC_MethodHook> callbacks, Class<?>[] parameterTypes, Class<?> returnType) {
            this.callbacks = callbacks;
            this.parameterTypes = parameterTypes;
            this.returnType = returnType;
        }
    }
}
   
