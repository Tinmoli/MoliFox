// 更新模式在提取 JVM 负载前运行，只处理本次已校验的同目录暂存文件。
#pragma once

namespace launcherUpdate {
constexpr DWORD processWaitMillis = 120000;
constexpr int replaceAttempts = 30;

struct WaitingProcesses {
    std::vector<HANDLE> handles;
    ~WaitingProcesses() { for (auto handle : handles) CloseHandle(handle); }

    void open(const std::wstring& list) {
        if (list.empty()) return;
        check(std::regex_match(list, std::wregex(L"[0-9]{1,10}(,[0-9]{1,10})?")), "Validate update process list");
        std::wstringstream values(list);
        std::wstring value;
        while (std::getline(values, value, L',')) {
            check(handles.size() < 2 && std::regex_match(value, std::wregex(L"[0-9]{1,10}")), "Validate update process ID");
            auto number = std::stoull(value);
            check(number > 0 && number <= MAXDWORD && number != GetCurrentProcessId(), "Validate update process ID range");
            auto handle = OpenProcess(SYNCHRONIZE, FALSE, static_cast<DWORD>(number));
            if (!handle) {
                check(GetLastError() == ERROR_INVALID_PARAMETER, "Observe old launcher process");
                continue;
            }
            handles.push_back(handle);
        }
    }

    void wait() const {
        if (!handles.empty()) {
            check(WaitForMultipleObjects(static_cast<DWORD>(handles.size()), handles.data(), TRUE, processWaitMillis) == WAIT_OBJECT_0,
                "Wait for old launcher processes");
        }
    }
};

struct OwnedFiles {
    fs::path staged;
    fs::path ready;
    ~OwnedFiles() {
        std::error_code ignored;
        if (!staged.empty()) {
            fs::remove(staged, ignored);
            fs::remove(fs::path(staged.wstring() + L".backup"), ignored);
        }
        if (!ready.empty()) fs::remove(ready, ignored);
    }
};

static void requireRegular(const fs::path& path) {
    auto attributes = GetFileAttributesW(path.c_str());
    check(attributes != INVALID_FILE_ATTRIBUTES && !(attributes & (FILE_ATTRIBUTE_DIRECTORY | FILE_ATTRIBUTE_REPARSE_POINT)),
        "Validate update file");
}

static void validatePaths(const fs::path& target, const fs::path& staged, const fs::path& ready, const fs::path& self) {
    check(target.is_absolute() && staged.is_absolute() && ready.is_absolute(), "Require absolute update paths");
    requireRegular(target);
    requireRegular(staged);
    check(fs::canonical(target.parent_path()) == fs::canonical(staged.parent_path()), "Require same-directory update staging");
    auto prefix = L"." + target.filename().wstring() + L".update-";
    auto filename = staged.filename().wstring();
    check(filename.rfind(prefix, 0) == 0, "Validate update staging name");
    auto identifier = filename.substr(prefix.size());
    check(std::regex_match(identifier, std::wregex(L"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")),
        "Validate update staging identifier");
    check(ready.filename() == fs::path(L"apply-" + identifier + L".ready") && !fs::exists(ready), "Validate update readiness path");
    check(!fs::equivalent(target, staged) && !fs::equivalent(target, self) && !fs::equivalent(staged, self), "Require distinct update files");
    check(!(GetFileAttributesW(target.c_str()) & FILE_ATTRIBUTE_READONLY), "Require writable launcher file");
    check(!fs::exists(fs::path(staged.wstring() + L".backup")), "Require unused backup staging path");
}

static void signalReady(const fs::path& ready) {
    auto handle = CreateFileW(ready.c_str(), GENERIC_WRITE, FILE_SHARE_READ | FILE_SHARE_DELETE, nullptr, CREATE_NEW, FILE_ATTRIBUTE_NORMAL, nullptr);
    check(handle != INVALID_HANDLE_VALUE, "Create update readiness signal");
    DWORD written = 0;
    auto success = WriteFile(handle, "ready", 5, &written, nullptr);
    CloseHandle(handle);
    check(success && written == 5, "Write update readiness signal");
}

static void saveBackup(const fs::path& target, const fs::path& staged) {
    auto backup = fs::path(target.wstring() + L".previous");
    auto attributes = GetFileAttributesW(backup.c_str());
    check(attributes == INVALID_FILE_ATTRIBUTES || !(attributes & (FILE_ATTRIBUTE_DIRECTORY | FILE_ATTRIBUTE_REPARSE_POINT)),
        "Validate previous launcher backup");
    auto pending = fs::path(staged.wstring() + L".backup");
    check(CopyFileW(target.c_str(), pending.c_str(), TRUE), "Copy previous launcher backup");
    check(MoveFileExW(pending.c_str(), backup.c_str(), MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH), "Publish previous launcher backup");
}

static void replace(const fs::path& target, const fs::path& staged) {
    for (int attempt = 0; attempt < replaceAttempts; ++attempt) {
        if (ReplaceFileW(target.c_str(), staged.c_str(), nullptr, REPLACEFILE_IGNORE_MERGE_ERRORS, nullptr, nullptr)) return;
        auto error = GetLastError();
        if (!fs::exists(target)) {
            CopyFileW(fs::path(target.wstring() + L".previous").c_str(), target.c_str(), TRUE);
            SetLastError(error);
            check(false, "Restore launcher after replacement failure");
        }
        bool busy = error == ERROR_ACCESS_DENIED || error == ERROR_SHARING_VIOLATION || error == ERROR_LOCK_VIOLATION;
        if (!busy || attempt + 1 == replaceAttempts) {
            SetLastError(error);
            check(false, "Replace launcher file");
        }
        Sleep(500);
    }
}

static void restart(const fs::path& target) {
    auto command = quote(target.wstring());
    STARTUPINFOW startup{sizeof(STARTUPINFOW)};
    PROCESS_INFORMATION process{};
    check(CreateProcessW(target.c_str(), command.data(), nullptr, nullptr, FALSE, 0, nullptr, target.parent_path().c_str(), &startup, &process),
        "Restart updated launcher");
    CloseHandle(process.hThread);
    CloseHandle(process.hProcess);
}

static int apply(const std::vector<std::wstring>& arguments) {
    OwnedFiles owned;
    try {
        check(arguments.size() == 7, "Validate update argument count");
        auto target = fs::path(arguments[2]).lexically_normal();
        auto staged = fs::path(arguments[3]).lexically_normal();
        auto ready = fs::path(arguments[6]).lexically_normal();
        wchar_t executable[32768];
        check(GetModuleFileNameW(nullptr, executable, 32768) != 0, "Locate update helper");
        auto self = fs::path(executable);
        validatePaths(target, staged, ready, self);
        owned.staged = staged;
        owned.ready = ready;
        auto expected = utf8(arguments[4]);
        check(std::regex_match(expected, std::regex("[0-9a-f]{64}")), "Validate update digest");
        check(sha256(staged) == expected && sha256(self) == expected, "Verify update digest before exit");
        auto original = sha256(target);
        WaitingProcesses processes;
        processes.open(arguments[5]);
        signalReady(ready);
        processes.wait();
        requireRegular(target);
        requireRegular(staged);
        check(sha256(staged) == expected && sha256(target) == original, "Verify update files after launcher exit");
        saveBackup(target, staged);
        replace(target, staged);
        restart(target);
        return 0;
    } catch (const std::exception& error) {
        // 更新助手的标准错误已重定向到本次更新日志，不启动 JVM 或额外窗口。
        std::cerr << "Launcher update failed: " << error.what() << std::endl;
        return 1;
    }
}
}
