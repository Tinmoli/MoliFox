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
#include <algorithm>
#include <filesystem>
#include <fstream>
#include <regex>
#include <sstream>
#include <stdexcept>
#include <string>
#include <vector>
#include "miniz/miniz.h"

namespace fs = std::filesystem;
static HWND preparationWindow = nullptr;
static HWND statusLabel = nullptr;
static bool cancelled = false;

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

static void showStatus(const wchar_t* text) {
    if (!preparationWindow) {
        WNDCLASSW type{};
        type.lpfnWndProc = preparationProc;
        type.hInstance = GetModuleHandleW(nullptr);
        type.lpszClassName = L"MoliFoxPreparation";
        type.hbrBackground = (HBRUSH)(COLOR_WINDOW + 1);
        type.hIcon = LoadIconW(type.hInstance, MAKEINTRESOURCEW(1));
        type.hCursor = LoadCursorW(nullptr, IDC_ARROW);
        RegisterClassW(&type);
        preparationWindow = CreateWindowExW(0, type.lpszClassName, L"MoliFox · 准备启动", WS_OVERLAPPED | WS_CAPTION | WS_SYSMENU,
            (GetSystemMetrics(SM_CXSCREEN) - 520) / 2, (GetSystemMetrics(SM_CYSCREEN) - 180) / 2, 520, 180, nullptr, nullptr, type.hInstance, nullptr);
        statusLabel = CreateWindowW(L"STATIC", text, WS_CHILD | WS_VISIBLE, 24, 28, 465, 75, preparationWindow, nullptr, type.hInstance, nullptr);
        SendMessageW(statusLabel, WM_SETFONT, (WPARAM)GetStockObject(DEFAULT_GUI_FONT), TRUE);
        ShowWindow(preparationWindow, SW_SHOW);
    }
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

struct HttpHandle {
    HINTERNET handle;
    ~HttpHandle() { if (handle) WinHttpCloseHandle(handle); }
    operator HINTERNET() const { return handle; }
};

static std::string download(const std::wstring& url, const fs::path& target = {}) {
    URL_COMPONENTS components{sizeof(URL_COMPONENTS)};
    components.dwHostNameLength = components.dwUrlPathLength = components.dwExtraInfoLength = (DWORD)-1;
    check(WinHttpCrackUrl(url.c_str(), 0, 0, &components), "Read download URL");
    check(components.nScheme == INTERNET_SCHEME_HTTPS, "Require HTTPS");
    std::wstring host(components.lpszHostName, components.dwHostNameLength);
    std::wstring route(components.lpszUrlPath, components.dwUrlPathLength);
    if (components.dwExtraInfoLength) route.append(components.lpszExtraInfo, components.dwExtraInfoLength);
    HttpHandle session{WinHttpOpen(L"MoliFox/0.3.0", WINHTTP_ACCESS_TYPE_AUTOMATIC_PROXY, WINHTTP_NO_PROXY_NAME, WINHTTP_NO_PROXY_BYPASS, 0)};
    check(session.handle != nullptr, "Open network connection");
    WinHttpSetTimeouts(session, 15000, 15000, 30000, 30000);
    HttpHandle connection{WinHttpConnect(session, host.c_str(), components.nPort, 0)};
    HttpHandle request{WinHttpOpenRequest(connection, L"GET", route.c_str(), nullptr, WINHTTP_NO_REFERER, WINHTTP_DEFAULT_ACCEPT_TYPES, WINHTTP_FLAG_SECURE)};
    check(request.handle != nullptr, "Prepare download");
    DWORD redirect = WINHTTP_OPTION_REDIRECT_POLICY_DISALLOW_HTTPS_TO_HTTP;
    WinHttpSetOption(request, WINHTTP_OPTION_REDIRECT_POLICY, &redirect, sizeof(redirect));
    check(WinHttpSendRequest(request, WINHTTP_NO_ADDITIONAL_HEADERS, 0, WINHTTP_NO_REQUEST_DATA, 0, 0, 0) && WinHttpReceiveResponse(request, nullptr), "Download runtime");
    DWORD status = 0, statusSize = sizeof(status);
    WinHttpQueryHeaders(request, WINHTTP_QUERY_STATUS_CODE | WINHTTP_QUERY_FLAG_NUMBER, nullptr, &status, &statusSize, nullptr);
    if (status != 200) throw std::runtime_error("Runtime download returned HTTP " + std::to_string(status));
    std::ofstream output;
    if (!target.empty()) { output.open(target, std::ios::binary | std::ios::trunc); check(output.good(), "Create download file"); }
    std::string body;
    char buffer[65536]; DWORD received = 0;
    while (true) {
        check(WinHttpReadData(request, buffer, sizeof(buffer), &received), "Read runtime download");
        if (!received) break;
        if (target.empty()) { body.append(buffer, received); if (body.size() > 4 * 1024 * 1024) throw std::runtime_error("Runtime metadata is too large"); }
        else { output.write(buffer, received); check(output.good(), "Write runtime download"); }
        pump();
    }
    return body;
}

static bool usableJava(const fs::path& path, const fs::path& directory) {
    if (!fs::is_regular_file(path)) return false;
    try {
        auto result = run(path, {L"-version"}, directory, true);
        std::smatch match;
        if (result.exitCode != 0 || !std::regex_search(result.output, match, std::regex("version \\\"([0-9]+)"))) return false;
        return std::stoi(match[1]) >= 17;
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

static fs::path prepareJava(const fs::path& data, const fs::path& temporary) {
    showStatus(L"首次启动：正在获取 Java 21 运行环境。\n文件会保存在旁边的 MoliFox 文件夹中。");
    auto metadata = download(L"https://api.adoptium.net/v3/assets/latest/21/hotspot?os=windows&architecture=x64&image_type=jre&vendor=eclipse");
    auto start = metadata.find("\"package\"");
    if (start == std::string::npos) throw std::runtime_error("No compatible Java runtime was returned");
    auto package = metadata.substr(start, metadata.find('}', start) - start + 1);
    std::string checksum = jsonString(package, "checksum");
    if (!std::regex_match(checksum, std::regex("[0-9a-fA-F]{64}"))) throw std::runtime_error("Invalid runtime checksum");
    auto archive = data / L"cache/downloads/java/java21.zip.part";
    download(wide(jsonString(package, "link")), archive);
    check(sha256(archive) == checksum, "Verify downloaded Java SHA-256");
    showStatus(L"正在解压 Java 运行环境……");
    auto staging = temporary / L"java-extract";
    fs::create_directories(staging);
    extractRuntime(archive, staging);
    fs::remove(archive);
    for (auto& directory : fs::directory_iterator(staging)) {
        auto java = directory.path() / L"bin/java.exe";
        if (usableJava(java, data)) {
            auto ready = data / L"runtimes/launcher-java21";
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

int WINAPI wWinMain(HINSTANCE instance, HINSTANCE, PWSTR, int) {
    fs::path data;
    try {
        wchar_t executable[32768]; check(GetModuleFileNameW(nullptr, executable, 32768) != 0, "Locate launcher");
        auto directory = fs::path(executable).parent_path();
        data = directory / L"MoliFox";
        fs::create_directories(data / L"dependencies/launcher"); fs::create_directories(data / L"cache/downloads/java");
        fs::create_directories(data / L"logs"); fs::create_directories(data / L"runtimes");
        RunTemporaryDirectory temporary(data);
        SetCurrentDirectoryW(directory.c_str());
        SetEnvironmentVariableW(L"MOLIFOX_DATA_DIR", data.c_str());
        SetEnvironmentVariableW(L"TEMP", temporary.path.c_str());
        SetEnvironmentVariableW(L"TMP", temporary.path.c_str());
        auto resource = FindResourceW(instance, MAKEINTRESOURCEW(101), RT_RCDATA);
        check(resource != nullptr, "Locate launcher payload");
        auto loaded = LoadResource(instance, resource);
        auto bytes = LockResource(loaded);
        auto size = SizeofResource(instance, resource);
        auto jar = data / L"dependencies/launcher/MoliFox-0.3.0.jar";
        auto pending = temporary.path / L"launcher-payload.part";
        { std::ofstream output(pending, std::ios::binary | std::ios::trunc); output.write((const char*)bytes, size); check(output.good(), "Extract launcher payload"); }
        if (fs::exists(jar) && sha256(jar) == sha256(pending)) fs::remove(pending);
        else check(MoveFileExW(pending.c_str(), jar.c_str(), MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH), "Install launcher payload");
        auto java = findJava(data);
        if (java.empty()) java = prepareJava(data, temporary.path);
        { std::ofstream log(data / L"logs/bootstrap.log", std::ios::binary); log << "Java: " << utf8(java.wstring()) << "\nData: " << utf8(data.wstring()) << "\n"; }
        auto javaw = java.parent_path() / L"javaw.exe";
        if (fs::exists(javaw)) java = javaw;
        if (preparationWindow) { DestroyWindow(preparationWindow); preparationWindow = nullptr; }
        auto result = run(java, {L"--enable-native-access=ALL-UNNAMED", L"-Djava.net.useSystemProxies=true", L"-Dfile.encoding=UTF-8", L"-Dstdout.encoding=UTF-8", L"-Dstderr.encoding=UTF-8",
            L"-Dmolifox.data.dir=" + data.wstring(), L"-Djava.io.tmpdir=" + temporary.path.wstring(),
            L"-Dmolifox.temp.dir=" + temporary.path.wstring(), L"-jar", jar.wstring()}, directory);
        return (int)result.exitCode;
    } catch (const std::exception& error) {
        if (preparationWindow) DestroyWindow(preparationWindow);
        if (cancelled) return 1;
        auto log = data / L"logs/bootstrap.log";
        try { std::ofstream output(log, std::ios::binary); output << error.what(); } catch (...) {}
        auto message = L"MoliFox 未能启动。\n\n" + wide(error.what()) + L"\n\n日志：" + log.wstring();
        MessageBoxW(nullptr, message.c_str(), L"MoliFox 启动失败", MB_ICONERROR);
        return 1;
    }
}
