package com.superchargedserver;

import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

@Getter
public class ChargedServerBridge {

    private final SuperChargedServer plugin;
    private Object chargedApi;
    private boolean hooked;

    public ChargedServerBridge(SuperChargedServer plugin) {
        this.plugin = plugin;
    }

    /**
     * ChargedServer's classes aren't on the compile classpath, so we bridge to it
     * purely via reflection at runtime. plugin.yml enforces the hard dependency,
     * so ChargedServer is guaranteed loaded before this runs.
     */
    public void hook() {
        Plugin chargedServer = Bukkit.getPluginManager().getPlugin("ChargedServer");
        if (chargedServer == null || !chargedServer.isEnabled()) {
            plugin.getLogger().warning("ChargedServer not found or not enabled \u2014 running without base plugin integration.");
            return;
        }

        try {
            Class<?> apiClass = Class.forName("com.chargedserver.api.ChargedAPI");
            Class<?> moduleClass = Class.forName("com.chargedserver.api.ChargedModule");
            Method getMethod = apiClass.getMethod("get");
            chargedApi = getMethod.invoke(null);

            Object moduleProxy = Proxy.newProxyInstance(
                    moduleClass.getClassLoader(),
                    new Class<?>[]{moduleClass},
                    new ModuleHandler()
            );

            Method registerModule = apiClass.getMethod("registerModule", moduleClass);
            registerModule.invoke(chargedApi, moduleProxy);

            hooked = true;
            plugin.getLogger().info("SuperChargedServer registered as a ChargedServer module.");
        } catch (ReflectiveOperationException e) {
            plugin.getLogger().severe("Failed to hook into ChargedServer API: " + e.getMessage());
        }
    }

    public void onUnload() {
        if (chargedApi == null) return;
        try {
            Method unregisterModule = chargedApi.getClass().getMethod("unregisterModule", String.class);
            unregisterModule.invoke(chargedApi, "SuperChargedServer");
        } catch (ReflectiveOperationException ignored) {
            // ChargedServer may already be shutting down; nothing else to do.
        }
        chargedApi = null;
        hooked = false;
    }

    private class ModuleHandler implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            switch (method.getName()) {
                case "name":
                    return "SuperChargedServer";
                case "onLoad":
                    chargedApi = args[0];
                    return null;
                case "onUnload":
                    chargedApi = null;
                    return null;
                default:
                    return null;
            }
        }
    }
}
