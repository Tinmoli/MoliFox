// 负责验证真人微软授权、三维皮肤显示和本地退出。

// Performs an explicitly interactive Microsoft UI check without retaining authentication secrets.
import com.google.gson.*;

import java.awt.*;
import java.io.File;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import javax.accessibility.*;

final class MicrosoftActionChecks {
    static void run(Robot robot, Window window, File output, Path root) throws Exception {
        UiSmoke.click(robot, "微软登录");
        UiSmoke.waitFor("在浏览器中完成登录");
        AtomicReference<String> code = new AtomicReference<>();
        EventQueue.invokeAndWait(() -> code.set(findCode(window.getAccessibleContext())));
        UiFunctionalChecks.require(
                code.get() != null, "The login dialog did not expose its device code");
        Path status = root.resolve("auth-status.json");
        Files.writeString(
                status,
                new Gson()
                        .toJson(
                                Map.of(
                                        "stage",
                                        "waiting-for-user",
                                        "userCode",
                                        code.get(),
                                        "verificationUri",
                                        "https://www.microsoft.com/link")));
        System.out.println("MICROSOFT_UI_WAITING_FOR_USER");
        UiSmoke.awaitFileState(
                "user completes real Microsoft authorization",
                15 * 60_000,
                () -> {
                    try {
                        return microsoftAccount(root) != null;
                    } catch (Exception pending) {
                        return false;
                    }
                });
        JsonObject account = microsoftAccount(root);
        Files.writeString(status, "{\"stage\":\"authorized\"}");
        UiSmoke.waitUntilGone("在浏览器中完成登录");
        UiSmoke.waitFor(account.get("name").getAsString());
        UiFunctionalChecks.require(
                account.has("skinUrl") && !account.get("skinUrl").getAsString().isBlank(),
                "Real account has no active skin URL");
        UiSmoke.click(robot, "刷新账户头像");
        UiSmoke.click(robot, "管理账户皮肤");
        UiSmoke.waitFor("完成");
        UiSmoke.dragSkinModel(robot);
        UiSmoke.capture(
                robot,
                window,
                new File(output.getParentFile(), "MoliFox-actions-microsoft.png"),
                true);
        UiSmoke.click(robot, "完成");
        UiFunctionalChecks.record(
                "microsoft.ui-login-and-skin",
                "real account persisted; authorization dialog closed; active skin URL supplied; refresh/3D skin controls worked; no remote skin upload");
        UiSmoke.click(robot, "注销当前账户");
        UiSmoke.waitFor("移除 " + account.get("name").getAsString() + "？");
        UiSmoke.clickTopButton(robot, "移除账户");
        UiSmoke.awaitFileState(
                "real UI account removed",
                10000,
                () -> {
                    try {
                        return microsoftAccount(root) == null;
                    } catch (Exception pending) {
                        return false;
                    }
                });
        Path credentials = root.resolve("profile/credentials");
        try (var files = Files.list(credentials)) {
            String id = account.get("id").getAsString();
            UiFunctionalChecks.require(
                    files.noneMatch(path -> path.getFileName().toString().contains(id)),
                    "Removed account still has a credential file");
        }
        UiFunctionalChecks.record(
                "microsoft.ui-signout",
                "actual UI removal cleared the account record and its credential file");
        Files.writeString(status, "{\"stage\":\"passed\"}");
    }

    private static JsonObject microsoftAccount(Path root) throws Exception {
        for (JsonElement item : UiFunctionalChecks.config(root).getAsJsonArray("accounts"))
            if (item.getAsJsonObject().get("type").getAsString().equals("Microsoft"))
                return item.getAsJsonObject();
        return null;
    }

    private static String findCode(AccessibleContext context) {
        if (context == null) return null;
        String name = context.getAccessibleName();
        if (name != null && name.matches("[A-Z0-9]{4}-?[A-Z0-9]{4}")) return name;
        for (int i = 0; i < context.getAccessibleChildrenCount(); i++) {
            Accessible child = context.getAccessibleChild(i);
            String result = child == null ? null : findCode(child.getAccessibleContext());
            if (result != null) return result;
        }
        return null;
    }
}
