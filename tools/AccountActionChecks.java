// 负责验证离线账户新增、切换和本地退出。

// Checks offline account validation, switching, avatar actions and removal through visible
// controls.
import java.awt.*;
import java.io.File;
import java.nio.file.Path;

final class AccountActionChecks {
    static void run(Robot robot, Window window, File output, Path root) throws Exception {
        UiSmoke.click(robot, "离线");
        UiSmoke.waitUntilGone("微软登录");
        UiSmoke.enterText("玩家名称", "非法名字!");
        UiFunctionalChecks.require(
                !UiSmoke.waitFor("添加账户").getAccessibleComponent().isEnabled(),
                "Invalid offline name remained addable");
        UiSmoke.enterText("玩家名称", "ActionPlayerA");
        UiSmoke.click(robot, "添加账户");
        UiSmoke.waitFor("ActionPlayerA");
        UiSmoke.click(robot, "添加离线账户");
        UiSmoke.enterText("玩家名称", "ActionPlayerB");
        UiSmoke.click(robot, "添加账户");
        UiSmoke.waitFor("ActionPlayerB");
        UiSmoke.click(robot, "A, ActionPlayerA, 离线角色");
        UiSmoke.awaitFileState(
                "offline account switched",
                10000,
                () -> {
                    try {
                        var data = UiFunctionalChecks.config(root);
                        String selected = data.get("activeAccountId").getAsString();
                        for (var account : data.getAsJsonArray("accounts"))
                            if (account.getAsJsonObject().get("id").getAsString().equals(selected))
                                return account.getAsJsonObject()
                                        .get("name")
                                        .getAsString()
                                        .equals("ActionPlayerA");
                        return false;
                    } catch (Exception pending) {
                        return false;
                    }
                });
        UiSmoke.click(robot, "刷新账户头像");
        UiSmoke.click(robot, "管理账户皮肤");
        UiSmoke.waitFor("完成");
        UiSmoke.click(robot, "完成");
        UiSmoke.click(robot, "注销当前账户");
        UiSmoke.waitFor("移除 ActionPlayerA？");
        UiSmoke.clickTopButton(robot, "移除账户");
        UiSmoke.awaitFileState(
                "account removed",
                10000,
                () -> {
                    try {
                        return UiFunctionalChecks.config(root).getAsJsonArray("accounts").size()
                                == 1;
                    } catch (Exception pending) {
                        return false;
                    }
                });
        UiFunctionalChecks.record(
                "accounts.offline",
                "invalid name blocked; two accounts added; selection persisted; avatar/skin actions clicked; selected account removed");
        UiSmoke.capture(
                robot,
                window,
                new File(output.getParentFile(), "MoliFox-actions-accounts.png"),
                true);
    }
}
