// 负责定位系统 Java、准备启动环境并运行内嵌的启动器 JAR。

#ifndef UNICODE
#define UNICODE
#endif
#ifndef _UNICODE
#define _UNICODE
#endif
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <winhttp.h>
#include <bcrypt.h>
#include <commctrl.h>
#include <shellapi.h>
#include <algorithm>
#include <atomic>
#include <cctype>
#include <filesystem>
#include <fstream>
#include <functional>
#include <iostream>
#include <memory>
#include <mutex>
#include <regex>
#include <sstream>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>
#include "miniz/miniz.h"
#include "NativeMessages.h"

namespace fs = std::filesystem;
static HWND preparationWindow = nullptr;
static HWND statusLabel = nullptr;
static HWND progressBar = nullptr;
static std::wstring statusBase;
static bool cancelled = false;
static const NativeMessages* messages = &nativeMessagesenUS;

static std::wstring wide(const std::string& value) {
    int size = MultiByteToWideChar(CP_UTF8, 0, value.data(), (int)value.size(), nullptr, 0);
    std::wstring result(size, 0);
    MultiByteToWideChar(CP_UTF8, 0, value.data(), (int)value.size(), result.data(), size);
    return result;
}

static std::string utf8(const std::wstring& value) {
    int size = WideCharToMultiByte(CP_UTF8, 0, value.data(), (int)value.size(), nullptr, 0, nullptr, nullptr);
    std::string result(size, 0);
    WideCharToMultiByte(CP_UTF8, 0, value.data(), (int)value.size(), result.data(), size, nullptr, nullptr);
    return result;
}

/** 设置里明确选择的语言优先；auto 或首次启动时跟随 Windows 区域语言。 */
static void selectNativeMessages(const fs::path& data) {
    std::string language;
    try {
        std::ifstream input(data / L"config/launcher.json", std::ios::binary);
        std::string settings((std::istreambuf_iterator<char>(input)), {});
        std::smatch match;
        if (std::regex_search(settings, match, std::regex("\\\"language\\\"\\s*:\\s*\\\"([^\\\"]+)\\\""))) language = match[1];
    } catch (...) {}
    if (language.empty() || language == "auto") {
        wchar_t locale[LOCALE_NAME_MAX_LENGTH]{};
        if (GetUserDefaultLocaleName(locale, LOCALE_NAME_MAX_LENGTH)) language = utf8(locale);
    }
    if (language.rfind("zh-CN", 0) == 0 || language.rfind("zh-SG", 0) == 0) messages = &nativeMessageszhCN;
    else if (language.rfind("zh-TW", 0) == 0 || language.rfind("zh-HK", 0) == 0 || language.rfind("zh-MO", 0) == 0) messages = &nativeMessageszhTW;
    else messages = &nativeMessagesenUS;
}

static void check(bool success, const char* action) {
    if (!success) throw std::runtime_error(std::string(action) + " (Windows error " + std::to_string(GetLastError()) + ")");
}

static void pump() {
    MSG message;
    while (PeekMessageW(&message, nullptr, 0, 0, PM_REMOVE)) {
        TranslateMessage(&message);
        DispatchMessageW(&message);
    }
    if (cancelled) throw std::runtime_error("Preparation cancelled");
}

static LRESULT CALLBACK preparationProc(HWND window, UINT message, WPARAM wParam, LPARAM lParam) {
    if (message == WM_CLOSE) { cancelled = true; DestroyWindow(window); return 0; }
    return DefWindowProcW(window, message, wParam, lParam);
}

static void setProgress(int percent) {
    if (!progressBar) return;
    const int value = std::clamp(percent, 0, 100);
    SendMessageW(progressBar, PBM_SETPOS, value, 0);
    if (statusLabel && !statusBase.empty()) {
        SetWindowTextW(statusLabel, (statusBase + L" " + std::to_wstring(value) + L"%").c_str());
    }
}

static void showStatus(const wchar_t* text) {
    if (!preparationWindow) {
        INITCOMMONCONTROLSEX controls{sizeof(INITCOMMONCONTROLSEX), ICC_PROGRESS_CLASS};
        InitCommonControlsEx(&controls);
        WNDCLASSW type{};
        type.lpfnWndProc = preparationProc;
        type.hInstance = GetModuleHandleW(nullptr);
        type.lpszClassName = L"MoliFoxPreparation";
        type.hbrBackground = (HBRUSH)(COLOR_WINDOW + 1);
        type.hIcon = LoadIconW(type.hInstance, MAKEINTRESOURCEW(1));
        type.hCursor = LoadCursorW(nullptr, IDC_ARROW);
        RegisterClassW(&type);
        // 下载期间主线程持续 pump，窗口保持可重绘；进度条让用户看到确实在推进。
        preparationWindow = CreateWindowExW(0, type.lpszClassName, messages->preparationTitle, WS_OVERLAPPED | WS_CAPTION | WS_SYSMENU,
            (GetSystemMetrics(SM_CXSCREEN) - 560) / 2, (GetSystemMetrics(SM_CYSCREEN) - 220) / 2, 560, 220, nullptr, nullptr, type.hInstance, nullptr);
        statusLabel = CreateWindowW(L"STATIC", text, WS_CHILD | WS_VISIBLE, 24, 28, 512, 82, preparationWindow, nullptr, type.hInstance, nullptr);
        SendMessageW(statusLabel, WM_SETFONT, (WPARAM)GetStockObject(DEFAULT_GUI_FONT), TRUE);
        progressBar = CreateWindowExW(0, PROGRESS_CLASSW, nullptr, WS_CHILD | WS_VISIBLE | PBS_SMOOTH, 24, 126, 512, 20, preparationWindow, nullptr, type.hInstance, nullptr);
        SendMessageW(progressBar, PBM_SETRANGE32, 0, 100);
        SendMessageW(progressBar, PBM_SETPOS, 0, 0);
        ShowWindow(preparationWindow, SW_SHOW);
    }
    statusBase = text;
    SetWindowTextW(statusLabel, text);
    pump();
}

/** Quote a single argument according to CreateProcess / CommandLineToArgvW rules. */
static std::wstring quote(const std::wstring& value) {
    std::wstring result = L"\"";
    size_t slashes = 0;
    for (wchar_t character : value) {
        if (character == L'\\') { ++slashes; continue; }
        result.append(slashes * (character == L'"' ? 2 : 1), L'\\');
        slashes = 0;
        if (character == L'"') result += L'\\';
        result += character;
    }
    result.append(slashes * 2, L'\\');
    return result + L'"';
}

struct ProcessResult { DWORD exitCode; std::string output; };
static ProcessResult run(const fs::path& executable, const std::vector<std::wstring>& arguments, const fs::path& directory, bool capture = false, ULONGLONG timeout = 15000) {
    std::wstring command = quote(executable.wstring());
    for (const auto& argument : arguments) command += L" " + quote(argument);
    HANDLE readPipe = nullptr, writePipe = nullptr;
    SECURITY_ATTRIBUTES security{sizeof(SECURITY_ATTRIBUTES), nullptr, TRUE};
    if (capture) { check(CreatePipe(&readPipe, &writePipe, &security, 0), "Create output pipe"); SetHandleInformation(readPipe, HANDLE_FLAG_INHERIT, 0); }
    STARTUPINFOW startup{sizeof(STARTUPINFOW)};
    if (capture) { startup.dwFlags = STARTF_USESTDHANDLES; startup.hStdOutput = writePipe; startup.hStdError = writePipe; startup.hStdInput = GetStdHandle(STD_INPUT_HANDLE); }
    PROCESS_INFORMATION process{};
    BOOL started = CreateProcessW(executable.c_str(), command.data(), nullptr, nullptr, capture, CREATE_NO_WINDOW, nullptr, directory.c_str(), &startup, &process);
    if (writePipe) CloseHandle(writePipe);
    if (!started) { if (readPipe) CloseHandle(readPipe); check(false, "Start Java or archive extraction"); }
    CloseHandle(process.hThread);
    std::string output;
    auto deadline = GetTickCount64() + timeout;
    while (WaitForSingleObject(process.hProcess, 50) == WAIT_TIMEOUT) {
        if (capture) {
            DWORD available = 0;
            while (PeekNamedPipe(readPipe, nullptr, 0, nullptr, &available, nullptr) && available) {
                char buffer[4096]; DWORD received = 0;
                if (!ReadFile(readPipe, buffer, std::min<DWORD>(sizeof(buffer), available), &received, nullptr)) break;
                output.append(buffer, received);
            }
            if (GetTickCount64() > deadline) { TerminateProcess(process.hProcess, 1); break; }
        }
        pump();
    }
    if (capture) {
        char buffer[4096]; DWORD received = 0;
        while (ReadFile(readPipe, buffer, sizeof(buffer), &received, nullptr) && received) output.append(buffer, received);
        CloseHandle(readPipe);
    }
    DWORD exitCode = 1; GetExitCodeProcess(process.hProcess, &exitCode); CloseHandle(process.hProcess);
    return {exitCode, output};
}

static std::string sha256(const fs::path& path) {
    BCRYPT_ALG_HANDLE algorithm = nullptr;
    BCRYPT_HASH_HANDLE hash = nullptr;
    check(BCryptOpenAlgorithmProvider(&algorithm, BCRYPT_SHA256_ALGORITHM, nullptr, 0) == 0, "Open SHA-256");
    check(BCryptCreateHash(algorithm, &hash, nullptr, 0, nullptr, 0, 0) == 0, "Create SHA-256");
    std::ifstream input(path, std::ios::binary);
    check(input.good(), "Read file for checksum");
    std::vector<char> buffer(1024 * 1024);
    while (input) { input.read(buffer.data(), buffer.size()); check(BCryptHashData(hash, (PUCHAR)buffer.data(), (ULONG)input.gcount(), 0) == 0, "Hash file"); }
    unsigned char digest[32];
    check(BCryptFinishHash(hash, digest, 32, 0) == 0, "Finish checksum");
    BCryptDestroyHash(hash); BCryptCloseAlgorithmProvider(algorithm, 0);
    const char* hex = "0123456789abcdef";
    std::string result;
    for (unsigned char value : digest) { result += hex[value >> 4]; result += hex[value & 15]; }
    return result;
}

#include "WindowsUpdate.h"

/** 直接对内存中的内嵌负载求摘要，避免每次启动都把整包写到临时文件再删。 */
static std::string sha256Bytes(const void* data, size_t size) {
    BCRYPT_ALG_HANDLE algorithm = nullptr;
    BCRYPT_HASH_HANDLE hash = nullptr;
    check(BCryptOpenAlgorithmProvider(&algorithm, BCRYPT_SHA256_ALGORITHM, nullptr, 0) == 0, "Open SHA-256");
    check(BCryptCreateHash(algorithm, &hash, nullptr, 0, nullptr, 0, 0) == 0, "Create SHA-256");
    auto remaining = size;
    auto cursor = static_cast<const unsigned char*>(data);
    while (remaining > 0) {
        auto chunk = remaining > 1024 * 1024 ? 1024 * 1024 : remaining;
        check(BCryptHashData(hash, (PUCHAR)cursor, (ULONG)chunk, 0) == 0, "Hash payload");
        cursor += chunk;
        remaining -= chunk;
    }
    unsigned char digest[32];
    check(BCryptFinishHash(hash, digest, 32, 0) == 0, "Finish checksum");
    BCryptDestroyHash(hash); BCryptCloseAlgorithmProvider(algorithm, 0);
    const char* hex = "0123456789abcdef";
    std::string result;
    for (unsigned char value : digest) { result += hex[value >> 4]; result += hex[value & 15]; }
    return result;
}

struct HttpHandle {
    HINTERNET handle;
    ~HttpHandle() { if (handle) WinHttpCloseHandle(handle); }
    operator HINTERNET() const { return handle; }
};

using DownloadProgress = std::function<void(unsigned long long, unsigned long long)>;

static std::string download(const std::wstring& url, const fs::path& target = {}, const DownloadProgress& progress = {}, int receiveTimeout = 60000) {
    URL_COMPONENTS components{sizeof(URL_COMPONENTS)};
    components.dwHostNameLength = components.dwUrlPathLength = components.dwExtraInfoLength = (DWORD)-1;
    check(WinHttpCrackUrl(url.c_str(), 0, 0, &components), "Read download URL");
    check(components.nScheme == INTERNET_SCHEME_HTTPS, "Require HTTPS");
    std::wstring host(components.lpszHostName, components.dwHostNameLength);
    std::wstring route(components.lpszUrlPath, components.dwUrlPathLength);
    if (components.dwExtraInfoLength) route.append(components.lpszExtraInfo, components.dwExtraInfoLength);
    HttpHandle session{WinHttpOpen(L"MoliFox/0.4.6", WINHTTP_ACCESS_TYPE_AUTOMATIC_PROXY, WINHTTP_NO_PROXY_NAME, WINHTTP_NO_PROXY_BYPASS, 0)};
    check(session.handle != nullptr, "Open network connection");
    // 12002 是 WinHTTP 超时；首次启动的 Java 下载在慢网络下需要更宽的接收窗口。
    WinHttpSetTimeouts(session, 15000, 30000, receiveTimeout, receiveTimeout);
    HttpHandle connection{WinHttpConnect(session, host.c_str(), components.nPort, 0)};
    HttpHandle request{WinHttpOpenRequest(connection, L"GET", route.c_str(), nullptr, WINHTTP_NO_REFERER, WINHTTP_DEFAULT_ACCEPT_TYPES, WINHTTP_FLAG_SECURE)};
    check(request.handle != nullptr, "Prepare download");
    DWORD redirect = WINHTTP_OPTION_REDIRECT_POLICY_DISALLOW_HTTPS_TO_HTTP;
    WinHttpSetOption(request, WINHTTP_OPTION_REDIRECT_POLICY, &redirect, sizeof(redirect));
    check(WinHttpSendRequest(request, WINHTTP_NO_ADDITIONAL_HEADERS, 0, WINHTTP_NO_REQUEST_DATA, 0, 0, 0) && WinHttpReceiveResponse(request, nullptr), "Download runtime");
    DWORD status = 0, statusSize = sizeof(status);
    WinHttpQueryHeaders(request, WINHTTP_QUERY_STATUS_CODE | WINHTTP_QUERY_FLAG_NUMBER, nullptr, &status, &statusSize, nullptr);
    if (status != 200) throw std::runtime_error("Runtime download returned HTTP " + std::to_string(status));
    unsigned long long total = 0;
    DWORD contentLength = 0, lengthSize = sizeof(contentLength);
    if (WinHttpQueryHeaders(request, WINHTTP_QUERY_CONTENT_LENGTH | WINHTTP_QUERY_FLAG_NUMBER, nullptr, &contentLength, &lengthSize, nullptr)) total = contentLength;
    std::ofstream output;
    if (!target.empty()) { output.open(target, std::ios::binary | std::ios::trunc); check(output.good(), "Create download file"); }
    std::string body;
    char buffer[65536]; DWORD received = 0;
    unsigned long long completed = 0;
    while (true) {
        check(WinHttpReadData(request, buffer, sizeof(buffer), &received), "Read runtime download");
        if (!received) break;
        completed += received;
        if (target.empty()) { body.append(buffer, received); if (body.size() > 4 * 1024 * 1024) throw std::runtime_error("Runtime metadata is too large"); }
        else { output.write(buffer, received); check(output.good(), "Write runtime download"); }
        if (progress) progress(completed, total);
    }
    return body;
}

struct DownloadOutcome {
    std::mutex mutex;
    std::string body;
    std::string error;
    std::atomic<bool> finished{false};
    std::atomic<unsigned long long> completed{0};
    std::atomic<unsigned long long> total{0};
};

/** WinHTTP 调用放到工作线程，主线程继续 pump；这样慢速连接时准备窗口不会显示“未响应”。 */
static std::string downloadAsync(const std::wstring& url, const fs::path& target = {}, bool showProgress = false, int receiveTimeout = 60000) {
    auto outcome = std::make_shared<DownloadOutcome>();
    std::thread worker([outcome, url, target, showProgress, receiveTimeout] {
        try {
            auto body = download(url, target, [outcome, showProgress](unsigned long long completed, unsigned long long total) {
                if (!showProgress) return;
                outcome->completed.store(completed);
                outcome->total.store(total);
            }, receiveTimeout);
            {
                std::lock_guard<std::mutex> lock(outcome->mutex);
                outcome->body = std::move(body);
            }
        } catch (const std::exception& error) {
            std::lock_guard<std::mutex> lock(outcome->mutex);
            outcome->error = error.what();
        }
        outcome->finished.store(true);
    });
    try {
        while (!outcome->finished.load()) {
            pump();
            if (showProgress) {
                const auto total = outcome->total.load();
                const auto completed = outcome->completed.load();
                setProgress(total ? (int)(completed * 100 / total) : 0);
            }
            Sleep(20);
        }
    } catch (...) {
        // 用户取消时下载线程可能仍在写临时文件；让它自行结束，不能阻塞窗口关闭。
        worker.detach();
        throw;
    }
    worker.join();
    std::lock_guard<std::mutex> lock(outcome->mutex);
    if (!outcome->error.empty()) throw std::runtime_error(outcome->error);
    return outcome->body;
}

static std::string downloadWithRetry(const std::wstring& url, const fs::path& target = {}, bool showProgress = false, int attempts = 4, int receiveTimeout = 60000) {
    std::string lastError;
    for (int attempt = 0; attempt < attempts; ++attempt) {
        try {
            return downloadAsync(url, target, showProgress, receiveTimeout);
        } catch (const std::exception& error) {
            if (cancelled) throw;
            lastError = error.what();
            if (attempt + 1 >= attempts) break;
            setProgress(0);
            Sleep(1200 * (attempt + 1));
        }
    }
    throw std::runtime_error(lastError);
}

// Windows 实测空闲工作集约 396→255 MiB；只降低初始堆和空闲保留，不限制游戏或启动器最大堆。
static const std::vector<std::wstring> launcherMemoryOptions = {
    L"-Xms32m", L"-XX:MinHeapFreeRatio=10", L"-XX:MaxHeapFreeRatio=20", L"-XX:G1PeriodicGCInterval=30000"
};
static bool launcherMemoryTuningSupported = false;

static bool usableJava(const fs::path& path, const fs::path& directory) {
    if (!fs::is_regular_file(path)) return false;
    try {
        auto probeOptions = launcherMemoryOptions;
        probeOptions.push_back(L"-version");
        auto result = run(path, probeOptions, directory, true);
        const auto tuningSupported = result.exitCode == 0;
        if (!tuningSupported) result = run(path, {L"-version"}, directory, true);
        std::smatch match;
        if (result.exitCode != 0 || !std::regex_search(result.output, match, std::regex("version \\\"([0-9]+)"))) return false;
        if (std::stoi(match[1]) < 17) return false;
        launcherMemoryTuningSupported = tuningSupported;
        return true;
    } catch (...) { return false; }
}

static fs::path findJava(const fs::path& data) {
    wchar_t environment[32768];
    if (GetEnvironmentVariableW(L"JAVA_HOME", environment, 32768)) {
        fs::path candidate = fs::path(environment) / L"bin/java.exe";
        if (usableJava(candidate, data)) return candidate;
    }
    if (GetEnvironmentVariableW(L"PATH", environment, 32768)) {
        std::wistringstream paths(environment);
        std::wstring entry;
        while (std::getline(paths, entry, L';')) {
            if (entry.empty()) continue;
            if (entry.front() == L'"' && entry.back() == L'"') entry = entry.substr(1, entry.size() - 2);
            auto candidate = fs::path(entry) / L"java.exe";
            if (usableJava(candidate, data)) return candidate;
        }
    }
    // Java installers can register a JVM without adding it to PATH.
    for (const auto* branch : {L"SOFTWARE\\JavaSoft\\JDK", L"SOFTWARE\\JavaSoft\\JRE", L"SOFTWARE\\JavaSoft\\Java Runtime Environment", L"SOFTWARE\\JavaSoft\\Java Development Kit"}) {
        HKEY key = nullptr;
        if (RegOpenKeyExW(HKEY_LOCAL_MACHINE, branch, 0, KEY_READ | KEY_WOW64_64KEY, &key) != ERROR_SUCCESS) continue;
        for (DWORD index = 0; ; ++index) {
            wchar_t version[256]; DWORD versionLength = 256;
            if (RegEnumKeyExW(key, index, version, &versionLength, nullptr, nullptr, nullptr, nullptr) != ERROR_SUCCESS) break;
            wchar_t javaHome[32768]; DWORD size = sizeof(javaHome);
            if (RegGetValueW(key, version, L"JavaHome", RRF_RT_REG_SZ, nullptr, javaHome, &size) == ERROR_SUCCESS) {
                auto candidate = fs::path(javaHome) / L"bin/java.exe";
                if (usableJava(candidate, data)) { RegCloseKey(key); return candidate; }
            }
        }
        RegCloseKey(key);
    }
    const fs::path runtimes = data / L"runtimes";
    if (fs::exists(runtimes)) {
        for (auto& directory : fs::directory_iterator(runtimes)) {
            for (const auto& candidate : {directory.path() / L"bin/java.exe", directory.path() / L"runtime/bin/java.exe"})
                if (usableJava(candidate, data)) return candidate;
        }
    }
    return {};
}

static std::string jsonString(const std::string& object, const std::string& key) {
    std::smatch match;
    if (!std::regex_search(object, match, std::regex("\"" + key + "\"\\s*:\\s*\"([^\"]+)\""))) throw std::runtime_error("Runtime metadata is missing " + key);
    return match[1];
}

/** Only used on a complete ZIP whose SHA-256 came from the official runtime API. */
static void extractRuntime(const fs::path& archive, const fs::path& destination) {
    auto size = fs::file_size(archive);
    if (size > 256 * 1024 * 1024) throw std::runtime_error("Runtime archive exceeds the size limit");
    std::vector<char> bytes((size_t)size);
    std::ifstream input(archive, std::ios::binary);
    input.read(bytes.data(), bytes.size());
    check(input.good(), "Read Java archive");
    mz_zip_archive zip{};
    check(mz_zip_reader_init_mem(&zip, bytes.data(), bytes.size(), 0) != 0, "Open Java ZIP");
    try {
        auto count = mz_zip_reader_get_num_files(&zip);
        if (count > 20000) throw std::runtime_error("Too many runtime files");
        for (mz_uint index = 0; index < count; ++index) {
            mz_zip_archive_file_stat entry{};
            check(mz_zip_reader_file_stat(&zip, index, &entry) != 0, "Read ZIP entry");
            std::string name = entry.m_filename;
            std::replace(name.begin(), name.end(), '\\', '/');
            auto relative = fs::path(wide(name));
            if (relative.has_root_path() || name.find(':') != std::string::npos) throw std::runtime_error("Invalid runtime entry");
            for (const auto& part : relative) if (part == L"..") throw std::runtime_error("Unsafe runtime ZIP path");
            if (((entry.m_external_attr >> 16) & 0170000) == 0120000) throw std::runtime_error("Runtime ZIP contains a symlink");
            auto target = destination / relative;
            if (entry.m_is_directory) { fs::create_directories(target); continue; }
            if (entry.m_uncomp_size > 256 * 1024 * 1024) throw std::runtime_error("Runtime entry exceeds the size limit");
            std::vector<char> content((size_t)entry.m_uncomp_size);
            check(mz_zip_reader_extract_to_mem(&zip, index, content.data(), content.size(), 0) != 0, "Extract and verify runtime file");
            fs::create_directories(target.parent_path());
            std::ofstream output(target, std::ios::binary | std::ios::trunc);
            if (!output) {
                // A host may protect its serviced CRT DLLs. Reuse them only if Java can start.
                const auto filename = target.filename().wstring();
                wchar_t system[32768]; GetSystemDirectoryW(system, 32768);
                bool crt = filename == L"msvcp140.dll" || filename == L"ucrtbase.dll" || filename == L"vcruntime140.dll" || filename == L"vcruntime140_1.dll";
                if (crt && fs::is_regular_file(fs::path(system) / filename)) continue;
                throw std::runtime_error("Cannot create runtime file: " + utf8(target.wstring()));
            }
            output.write(content.data(), content.size());
            check(output.good(), "Save runtime file");
            pump();
        }
    } catch (...) { mz_zip_reader_end(&zip); throw; }
    mz_zip_reader_end(&zip);
}

/** 缺少系统 Java 时先征求同意；下载 Java 属于网络与磁盘操作，不能静默执行。 */
static bool confirmJavaDownload() {
    return MessageBoxW(
               nullptr,
               messages->javaRequiredMessage,
               messages->javaRequiredTitle,
               MB_YESNO | MB_ICONQUESTION | MB_DEFBUTTON2) == IDYES;
}

// 32 位外壳同时服务 x64 与 ARM64：GetNativeSystemInfo 返回系统真实架构，
// 不受 WOW64/ARM64 模拟层影响。这里不静态导入 IsWow64Process2，兼容旧系统。
static bool windowsArm64() {
    SYSTEM_INFO info{};
    GetNativeSystemInfo(&info);
    return info.wProcessorArchitecture == 12; // PROCESSOR_ARCHITECTURE_ARM64
}

// x64 首次下载改用 Temurin 17：与 17+ 门槛一致，兼容性更保守；17 完全满足启动器运行需求。
static constexpr wchar_t javaMetadataX64[] = L"https://api.adoptium.net/v3/assets/latest/17/hotspot?os=windows&architecture=x64&image_type=jre&vendor=eclipse";
// Temurin 17 没有 Windows ARM64 构建，ARM64 分支只能保持 21。
static constexpr wchar_t javaMetadataArm64[] = L"https://api.adoptium.net/v3/assets/latest/21/hotspot?os=windows&architecture=aarch64&image_type=jre&vendor=eclipse";
// Adoptium API 与 GitHub 在部分网络不可达；TUNA 镜像提供同一 Temurin 归档。
static constexpr wchar_t javaMirrorX64[] = L"https://mirrors.tuna.tsinghua.edu.cn/Adoptium/17/jre/x64/windows/OpenJDK17U-jre_x64_windows_hotspot_17.0.21_9.zip";
static constexpr wchar_t javaMirrorArm64[] = L"https://mirrors.tuna.tsinghua.edu.cn/Adoptium/21/jre/aarch64/windows/OpenJDK21U-jre_aarch64_windows_hotspot_21.0.12.1_1.zip";
static constexpr const char* javaMirrorX64Sha256 = "fe4656bd78fb09812dc38c49da777f79cc33e7ee435a254be82f9e7f40d9d117";
static constexpr const char* javaMirrorArm64Sha256 = "e82cc17e0bf89a25b0b0ed106d072f2ea420587d0a6870534b71b1dce3ae28c3";

static fs::path prepareJava(const fs::path& data, const fs::path& temporary) {
    showStatus(messages->javaPreparing);
    setProgress(0);
    const bool arm64 = windowsArm64();
    const wchar_t* metadataUrl = arm64 ? javaMetadataArm64 : javaMetadataX64;
    const wchar_t* mirrorUrl = arm64 ? javaMirrorArm64 : javaMirrorX64;
    const char* mirrorSha256 = arm64 ? javaMirrorArm64Sha256 : javaMirrorX64Sha256;
    // 归档与安装目录按下载版本命名（java17/java21），不覆盖旧版用户已装的 launcher-java21；
    // findJava 扫描 runtimes/ 下所有子目录，旧目录里的 Java 21 照常被识别，升级用户不会重复下载。
    const wchar_t* runtimeName = arm64 ? L"launcher-java21" : L"launcher-java17";
    std::wstring url;
    std::string checksum;
    try {
        auto metadata = downloadWithRetry(metadataUrl, {}, false, 2, 15000);
        auto start = metadata.find("\"package\"");
        if (start == std::string::npos) throw std::runtime_error("No compatible Java runtime was returned");
        auto package = metadata.substr(start, metadata.find('}', start) - start + 1);
        checksum = jsonString(package, "checksum");
        if (!std::regex_match(checksum, std::regex("[0-9a-fA-F]{64}"))) throw std::runtime_error("Invalid runtime checksum");
        url = wide(jsonString(package, "link"));
    } catch (const std::exception&) {
        if (cancelled) throw;
        // 元数据接口失败时直接走镜像；归档仍然要过 SHA-256 校验。
        url = mirrorUrl;
        checksum = mirrorSha256;
    }
    auto archive = data / L"cache/downloads/java" / (std::wstring(arm64 ? L"java21" : L"java17") + L".zip.part");
    try {
        downloadWithRetry(url, archive, true, 3);
    } catch (const std::exception&) {
        if (cancelled || url == mirrorUrl) throw;
        url = mirrorUrl;
        checksum = mirrorSha256;
        downloadWithRetry(url, archive, true, 3);
    }
    std::transform(checksum.begin(), checksum.end(), checksum.begin(), [](unsigned char value) { return (char)std::tolower(value); });
    check(sha256(archive) == checksum, "Verify downloaded Java SHA-256");
    setProgress(100);
    showStatus(messages->javaExtracting);
    auto staging = temporary / L"java-extract";
    fs::create_directories(staging);
    extractRuntime(archive, staging);
    fs::remove(archive);
    for (auto& directory : fs::directory_iterator(staging)) {
        auto java = directory.path() / L"bin/java.exe";
        if (usableJava(java, data)) {
            auto ready = data / L"runtimes" / runtimeName;
            if (!fs::exists(ready)) fs::rename(directory.path(), ready);
            return ready / L"bin/java.exe";
        }
    }
    throw std::runtime_error("Downloaded Java could not start");
}

// Native ownership lasts until the Java process exits, when loaded JNI files can be removed.
struct RunTemporaryDirectory {
    fs::path root;
    fs::path path;
    explicit RunTemporaryDirectory(const fs::path& data) : root(data / L"cache/tmp") {
        fs::create_directories(root);
        path = root / (L"session-" + std::to_wstring(GetCurrentProcessId()) + L"-" + std::to_wstring(GetTickCount64()));
        check(fs::create_directory(path), "Create private launcher temporary directory");
    }
    ~RunTemporaryDirectory() {
        try {
            auto target = fs::weakly_canonical(path);
            auto expectedRoot = fs::weakly_canonical(root);
            auto attributes = GetFileAttributesW(path.c_str());
            if (target.parent_path() == expectedRoot && path.filename().wstring().rfind(L"session-", 0) == 0 &&
                attributes != INVALID_FILE_ATTRIBUTES && !(attributes & FILE_ATTRIBUTE_REPARSE_POINT)) {
                std::error_code ignored;
                fs::remove_all(path, ignored);
            }
        } catch (...) { /* A failed cleanup must not hide the game/launcher exit result. */ }
    }
};

// 原生环境尚未准备好时使用系统任务对话框，技术错误折叠显示。
static void showStartupFailure(const std::wstring& details, const fs::path& log) {
    const auto location = std::wstring(messages->logLocation) + L" " + log.wstring() + L"\n" + messages->provideLog;
    TASKDIALOGCONFIG configuration{};
    configuration.cbSize = sizeof(configuration);
    configuration.dwFlags = TDF_SIZE_TO_CONTENT | TDF_ALLOW_DIALOG_CANCELLATION;
    configuration.dwCommonButtons = TDCBF_CLOSE_BUTTON;
    configuration.pszWindowTitle = messages->startupWindowTitle;
    configuration.pszMainInstruction = messages->startupInstruction;
    configuration.pszContent = location.c_str();
    configuration.pszExpandedInformation = details.c_str();
    configuration.pszExpandedControlText = messages->showDetails;
    configuration.pszCollapsedControlText = messages->hideDetails;
    // 部分环境仍加载旧版公共控件，禁止把新 API 固定写入 EXE 导入表。
    const auto controls = LoadLibraryExW(L"comctl32.dll", nullptr, LOAD_LIBRARY_SEARCH_SYSTEM32);
    using ShowTaskDialog = HRESULT(WINAPI*)(const TASKDIALOGCONFIG*, int*, int*, BOOL*);
    const auto showDialog = controls
        ? reinterpret_cast<ShowTaskDialog>(GetProcAddress(controls, "TaskDialogIndirect"))
        : nullptr;
    if (!showDialog || FAILED(showDialog(&configuration, nullptr, nullptr, nullptr))) {
        const auto fallback = details + L"\n\n" + location;
        MessageBoxW(nullptr, fallback.c_str(), configuration.pszWindowTitle, MB_ICONERROR);
    }
    if (controls) FreeLibrary(controls);
}

// 当前负载校验成功后才清理同目录旧版本；只删官方载荷命名，不接触用户改名的启动器 EXE。
static void removeOldLauncherPayloads(const fs::path& directory, const fs::path& current) noexcept {
    try {
        std::error_code error;
        for (const auto& entry : fs::directory_iterator(directory, error)) {
            if (error) break;
            const auto path = entry.path();
            const auto name = path.filename().wstring();
            const auto status = entry.symlink_status(error);
            if (error) { error.clear(); continue; }
            const bool officialPayload = name.rfind(L"MoliFox-", 0) == 0 && name.size() > 12 && name.substr(name.size() - 4) == L".jar";
            if (path != current && officialPayload && fs::is_regular_file(status)) fs::remove(path, error);
            error.clear();
        }
    } catch (...) { /* 旧载荷清理失败只占用磁盘，下次启动再试。 */ }
}

int WINAPI wWinMain(HINSTANCE instance, HINSTANCE, PWSTR, int) {
    int count = 0;
    auto values = CommandLineToArgvW(GetCommandLineW(), &count);
    if (values) {
        std::vector<std::wstring> arguments(values, values + count);
        LocalFree(values);
        if (arguments.size() > 1 && arguments[1] == L"--molifox-apply-update") return launcherUpdate::apply(arguments);
    }
    fs::path data;
    try {
        wchar_t executable[32768]; check(GetModuleFileNameW(nullptr, executable, 32768) != 0, "Locate launcher");
        auto directory = fs::path(executable).parent_path();
        data = directory / L"MoliFox";
        fs::create_directories(data / L"dependencies/launcher"); fs::create_directories(data / L"cache/downloads/java");
        fs::create_directories(data / L"logs"); fs::create_directories(data / L"runtimes");
        selectNativeMessages(data);
        RunTemporaryDirectory temporary(data);
        SetCurrentDirectoryW(directory.c_str());
        SetEnvironmentVariableW(L"MOLIFOX_DATA_DIR", data.c_str());
        // 传给 JVM：自动更新替换脚本要靠它定位当前便携版 exe（开发环境 java -jar 没有这个变量）。
        SetEnvironmentVariableW(L"MOLIFOX_BUNDLE_PATH", executable);
        // JVM 侧据此监视原生外壳；外壳被任务管理器强杀时，JVM 与联机核心不能继续留在后台。
        SetEnvironmentVariableW(L"MOLIFOX_WRAPPER_PID", std::to_wstring(GetCurrentProcessId()).c_str());
        // 更新成功后会删除旧版本副本；上次中断留下的也在这里清掉，避免目录里堆积 .previous。
        launcherUpdate::removePrevious(fs::path(executable));
        SetEnvironmentVariableW(L"TEMP", temporary.path.c_str());
        SetEnvironmentVariableW(L"TMP", temporary.path.c_str());
        auto resource = FindResourceW(instance, MAKEINTRESOURCEW(101), RT_RCDATA);
        check(resource != nullptr, "Locate launcher payload");
        auto loaded = LoadResource(instance, resource);
        auto bytes = LockResource(loaded);
        auto size = SizeofResource(instance, resource);
        auto jar = data / L"dependencies/launcher/MoliFox.jar";
        // 已有负载与内嵌内容一致时直接使用，省掉一次整包写入和临时文件校验。
        if (!fs::exists(jar) || sha256(jar) != sha256Bytes(bytes, size)) {
            auto pending = temporary.path / L"launcher-payload.part";
            { std::ofstream output(pending, std::ios::binary | std::ios::trunc); output.write((const char*)bytes, size); check(output.good(), "Extract launcher payload"); }
            check(MoveFileExW(pending.c_str(), jar.c_str(), MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH), "Install launcher payload");
        }
        removeOldLauncherPayloads(jar.parent_path(), jar);
        auto java = findJava(data);
        if (java.empty()) {
            if (!confirmJavaDownload()) {
                MessageBoxW(
                    nullptr,
                    messages->javaDownloadCancelled,
                    messages->javaRequiredTitle,
                    MB_OK | MB_ICONINFORMATION);
                return 2;
            }
            java = prepareJava(data, temporary.path);
        }
        { std::ofstream log(data / L"logs/bootstrap.log", std::ios::binary); log << "Java: " << utf8(java.wstring()) << "\nData: " << utf8(data.wstring()) << "\n"; }
        auto javaw = java.parent_path() / L"javaw.exe";
        if (fs::exists(javaw)) java = javaw;
        if (preparationWindow) { DestroyWindow(preparationWindow); preparationWindow = nullptr; }
        auto options = launcherMemoryTuningSupported ? launcherMemoryOptions : std::vector<std::wstring>{L"-Xms32m"};
        const std::vector<std::wstring> applicationOptions{L"--enable-native-access=ALL-UNNAMED", L"-Djava.net.useSystemProxies=true", L"-Dfile.encoding=UTF-8", L"-Dstdout.encoding=UTF-8", L"-Dstderr.encoding=UTF-8",
            L"-Dmolifox.data.dir=" + data.wstring(), L"-Djava.io.tmpdir=" + temporary.path.wstring(),
            L"-Dmolifox.temp.dir=" + temporary.path.wstring(), L"-jar", jar.wstring()};
        options.insert(options.end(), applicationOptions.begin(), applicationOptions.end());
        auto result = run(java, options, directory);
        return (int)result.exitCode;
    } catch (const std::exception& error) {
        if (preparationWindow) DestroyWindow(preparationWindow);
        if (cancelled) return 1;
        auto log = data / L"logs/bootstrap.log";
        try { std::ofstream output(log, std::ios::binary); output << error.what(); } catch (...) {}
        showStartupFailure(wide(error.what()), log);
        return 1;
    }
}
