#include <vulkan/vulkan.h>

#include "../adrenotools/include/adrenotools/driver.h"
#include <android/api-level.h>
#include <android/log.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>
#include "file_utils.h"

#define LOG_TAG "System.out"
#define printf(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define APP_CACHE_DIR "/data/data/app.winnative/cache"
#define MEMFREE(x) \
    do { \
        if (x != NULL) { \
            free(x); \
            x = NULL; \
        } \
    } \
    while(0)
#define LIBVULKAN_PATH "/system/lib64/libvulkan.so"

VkInstance instance;
VkPhysicalDevice physicalDevice = VK_NULL_HANDLE;
PFN_vkGetPhysicalDeviceProperties getPhysicalDeviceProperties;
PFN_vkEnumerateDeviceExtensionProperties enumerateDeviceExtensionProperties;
PFN_vkEnumeratePhysicalDevices enumeratePhysicalDevices;
PFN_vkDestroyInstance destroyInstance;

static void *vulkan_handle = NULL;

static char *get_native_library_dir(JNIEnv *env, jobject context) {
  char *native_libdir = NULL;

  if (context == NULL)
    return NULL;

  jclass class_ =
      (*env)->FindClass(env, "com/winlator/cmod/shared/android/AppUtils");
  if (class_ == NULL) {
    (*env)->ExceptionClear(env);
    return NULL;
  }

  jmethodID getNativeLibraryDir = (*env)->GetStaticMethodID(
      env, class_, "getNativeLibDir",
      "(Landroid/content/Context;)Ljava/lang/String;");
  if (getNativeLibraryDir == NULL) {
    (*env)->ExceptionClear(env);
    return NULL;
  }

  jstring nativeLibDir = (jstring)(*env)->CallStaticObjectMethod(
      env, class_, getNativeLibraryDir, context);
  if ((*env)->ExceptionCheck(env)) {
    (*env)->ExceptionClear(env);
    return NULL;
  }

  if (nativeLibDir)
    native_libdir = (char *)(*env)->GetStringUTFChars(env, nativeLibDir, NULL);

  return native_libdir;
}

static char *get_driver_path(JNIEnv *env, jobject context,
                             const char *driver_name) {
  char *driver_path = NULL;
  char *absolute_path;

  if (context == NULL)
    return NULL;

  jclass contextWrapperClass =
      (*env)->FindClass(env, "android/content/ContextWrapper");
  if (contextWrapperClass == NULL) {
    (*env)->ExceptionClear(env);
    return NULL;
  }

  jmethodID getFilesDir = (*env)->GetMethodID(
      env, contextWrapperClass, "getFilesDir", "()Ljava/io/File;");
  if (getFilesDir == NULL) {
    (*env)->ExceptionClear(env);
    return NULL;
  }

  jobject filesDirObj = (*env)->CallObjectMethod(env, context, getFilesDir);
  if ((*env)->ExceptionCheck(env)) {
    (*env)->ExceptionClear(env);
    return NULL;
  }
  if (filesDirObj == NULL)
    return NULL;

  jclass fileClass = (*env)->GetObjectClass(env, filesDirObj);
  if (fileClass == NULL) {
    (*env)->ExceptionClear(env);
    return NULL;
  }

  jmethodID getAbsolutePath = (*env)->GetMethodID(
      env, fileClass, "getAbsolutePath", "()Ljava/lang/String;");
  if (getAbsolutePath == NULL) {
    (*env)->ExceptionClear(env);
    return NULL;
  }

  jstring absolutePath =
      (jstring)(*env)->CallObjectMethod(env, filesDirObj, getAbsolutePath);
  if ((*env)->ExceptionCheck(env)) {
    (*env)->ExceptionClear(env);
    return NULL;
  }

  if (absolutePath) {
    absolute_path = (char *)(*env)->GetStringUTFChars(env, absolutePath, NULL);
    asprintf(&driver_path, "%s/contents/adrenotools/%s/", absolute_path,
             driver_name);
    (*env)->ReleaseStringUTFChars(env, absolutePath, absolute_path);
  }

  return driver_path;
}

static char *get_library_name(JNIEnv *env, jobject context,
                              const char *driver_name) {
  char *library_name = NULL;

  if (context == NULL)
    return NULL;

  jclass adrenotoolsManager = (*env)->FindClass(
      env, "com/winlator/cmod/runtime/content/AdrenotoolsManager");
  if (adrenotoolsManager == NULL) {
    (*env)->ExceptionClear(env);
    return NULL;
  }

  jmethodID constructor = (*env)->GetMethodID(env, adrenotoolsManager, "<init>",
                                              "(Landroid/content/Context;)V");
  if (constructor == NULL) {
    (*env)->ExceptionClear(env);
    return NULL;
  }

  jobject adrenotoolsManagerObj =
      (*env)->NewObject(env, adrenotoolsManager, constructor, context);
  if ((*env)->ExceptionCheck(env)) {
    (*env)->ExceptionClear(env);
    return NULL;
  }
  if (adrenotoolsManagerObj == NULL)
    return NULL;

  jmethodID getLibraryName =
      (*env)->GetMethodID(env, adrenotoolsManager, "getLibraryName",
                          "(Ljava/lang/String;)Ljava/lang/String;");
  if (getLibraryName == NULL) {
    (*env)->ExceptionClear(env);
    return NULL;
  }

  jstring driverName = (*env)->NewStringUTF(env, driver_name);
  jstring libraryName = (jstring)(*env)->CallObjectMethod(
      env, adrenotoolsManagerObj, getLibraryName, driverName);
  if ((*env)->ExceptionCheck(env)) {
    (*env)->ExceptionClear(env);
    return NULL;
  }

  if (libraryName)
    library_name = (char *)(*env)->GetStringUTFChars(env, libraryName, NULL);

  return library_name;
}

static void preload_first_existing(const char **candidates) {
  for (int i = 0; candidates[i]; i++) {
    if (dlopen(candidates[i], RTLD_GLOBAL | RTLD_NOW))
      return;
  }
}

static void preload_vendor_icd_deps() {
  // Keep OEM Vulkan ICD dependencies globally visible before the loader pulls
  // them in, or DXVK can misreport missing VK_KHR_surface.
  const char *jpeg_candidates[] = {
      "/system/lib64/libjpeg.so",
      "/system_ext/lib64/libjpeg.so",
      "libjpeg.so",
      NULL,
  };
  preload_first_existing(jpeg_candidates);

  const char *crypto_candidates[] = {
      "libcrypto.so",
      NULL,
  };
  preload_first_existing(crypto_candidates);
}

void *winlator_open_system_vulkan(void) {
  preload_vendor_icd_deps();
  return dlopen(LIBVULKAN_PATH, RTLD_LOCAL | RTLD_NOW);
}

void *winlator_open_vulkan(JNIEnv *env, jobject context, const char *driver_name) {
  if (!driver_name || strcmp(driver_name, "System") == 0) {
    return winlator_open_system_vulkan();
  }

  preload_vendor_icd_deps();

  const char *driver_path = get_driver_path(env, context, driver_name);
  if (!driver_path || access(driver_path, F_OK) != 0) {
    return winlator_open_system_vulkan();
  }

  char *library_name = get_library_name(env, context, driver_name);
  char *native_library_dir = get_native_library_dir(env, context);
  if (!library_name || !native_library_dir) {
    return winlator_open_system_vulkan();
  }

  char *tmpdir = NULL;
  asprintf(&tmpdir, "%s%s", driver_path, "temp");
  mkdir(tmpdir, S_IRWXU | S_IRWXG);

  void *handle = adrenotools_open_libvulkan(
      RTLD_LOCAL | RTLD_NOW,
      ADRENOTOOLS_DRIVER_CUSTOM, tmpdir,
      native_library_dir, driver_path, library_name, NULL,
      NULL);
  if (!handle) return winlator_open_system_vulkan();
  return handle;
}

static void init_original_vulkan() {
  vulkan_handle = winlator_open_system_vulkan();
}

static void init_vulkan(JNIEnv *env, jobject context, const char *driver_name) {
  vulkan_handle = winlator_open_vulkan(env, context, driver_name);
}

static VkResult create_instance(jstring driverName, JNIEnv *env,
                                jobject context) {
  VkResult result;
  VkInstanceCreateInfo create_info = {};
  char *driver_name = NULL;

  if (driverName != NULL)
    driver_name = (char *)(*env)->GetStringUTFChars(env, driverName, NULL);

  if (driver_name && strcmp(driver_name, "System"))
    init_vulkan(env, context, driver_name);
  else
    init_original_vulkan();

  if (!vulkan_handle)
    return VK_ERROR_INITIALIZATION_FAILED;

  PFN_vkGetInstanceProcAddr gip =
      (PFN_vkGetInstanceProcAddr)dlsym(vulkan_handle, "vkGetInstanceProcAddr");
  PFN_vkCreateInstance createInstance =
      (PFN_vkCreateInstance)dlsym(vulkan_handle, "vkCreateInstance");
  PFN_vkEnumerateInstanceVersion enumerateInstanceVersion =
      (PFN_vkEnumerateInstanceVersion)dlsym(vulkan_handle,
                                            "vkEnumerateInstanceVersion");

  if (!gip || !createInstance || !enumerateInstanceVersion)
    return VK_ERROR_INITIALIZATION_FAILED;

  int apiLevel = android_get_device_api_level();

  VkApplicationInfo app_info = {};
  app_info.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
  app_info.pApplicationName = "WinNative";
  app_info.applicationVersion = VK_MAKE_VERSION(1, 0, 0);
  app_info.pEngineName = "WinNative";
  app_info.engineVersion = VK_MAKE_VERSION(1, 0, 0);
  if (apiLevel > 32)
    app_info.apiVersion = VK_API_VERSION_1_0;
  else
    enumerateInstanceVersion(&app_info.apiVersion);

  create_info.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
  create_info.pNext = NULL;
  create_info.flags = 0;
  create_info.pApplicationInfo = &app_info;
  create_info.enabledLayerCount = 0;
  create_info.enabledExtensionCount = 0;

  result = createInstance(&create_info, NULL, &instance);

  if (result != VK_SUCCESS)
    return result;

  getPhysicalDeviceProperties = (PFN_vkGetPhysicalDeviceProperties)gip(
      instance, "vkGetPhysicalDeviceProperties");
  destroyInstance = (PFN_vkDestroyInstance)gip(instance, "vkDestroyInstance");
  enumerateDeviceExtensionProperties =
      (PFN_vkEnumerateDeviceExtensionProperties)gip(
          instance, "vkEnumerateDeviceExtensionProperties");
  enumeratePhysicalDevices = (PFN_vkEnumeratePhysicalDevices)gip(
      instance, "vkEnumeratePhysicalDevices");

  if (!getPhysicalDeviceProperties || !destroyInstance ||
      !enumerateDeviceExtensionProperties || !enumeratePhysicalDevices)
    return VK_ERROR_INITIALIZATION_FAILED;

  return VK_SUCCESS;
}

static VkResult enumerate_physical_devices() {
  VkResult result;
  uint32_t deviceCount = 0;

  result = enumeratePhysicalDevices(instance, &deviceCount, NULL);

  if (result != VK_SUCCESS)
    return result;

  if (deviceCount < 1)
    return VK_ERROR_INITIALIZATION_FAILED;

  VkPhysicalDevice *pdevices = malloc(sizeof(VkPhysicalDevice) * deviceCount);
  if (!pdevices)
    return VK_ERROR_OUT_OF_HOST_MEMORY;

  result = enumeratePhysicalDevices(instance, &deviceCount, pdevices);

  if (result != VK_SUCCESS) {
    free(pdevices);
    return result;
  }

  physicalDevice = pdevices[0];
  free(pdevices);

  if (physicalDevice == VK_NULL_HANDLE)
    return VK_ERROR_INITIALIZATION_FAILED;

  return VK_SUCCESS;
}

JNIEXPORT jstring JNICALL
Java_com_winlator_cmod_runtime_system_GPUInformation_getVulkanVersion(
    JNIEnv *env, jclass obj, jstring driverName, jobject context) {
  VkPhysicalDeviceProperties props = {};
  char *driverVersion;

  if (create_instance(driverName, env, context) != VK_SUCCESS) {
    printf("Failed to create instance");
    return (*env)->NewStringUTF(env, "Unknown");
  }

  if (enumerate_physical_devices() != VK_SUCCESS) {
    printf("Failed to query physical devices");
    return (*env)->NewStringUTF(env, "Unknown");
  }

  getPhysicalDeviceProperties(physicalDevice, &props);
  uint32_t api_version_major = VK_VERSION_MAJOR(props.apiVersion);
  uint32_t api_version_minor = VK_VERSION_MINOR(props.apiVersion);
  uint32_t api_version_patch = VK_VERSION_PATCH(props.apiVersion);
  asprintf(&driverVersion, "%d.%d.%d", api_version_major, api_version_minor,
           api_version_patch);

  jstring result = (*env)->NewStringUTF(env, driverVersion);
  free(driverVersion);

  destroyInstance(instance, NULL);

  if (vulkan_handle) {
    dlclose(vulkan_handle);
    vulkan_handle = NULL;
  }

  return result;
}

JNIEXPORT jint JNICALL
Java_com_winlator_cmod_runtime_system_GPUInformation_getVendorID(
    JNIEnv *env, jclass obj, jstring driverName, jobject context) {
  VkPhysicalDeviceProperties props = {};
  uint32_t vendorID;

  if (create_instance(driverName, env, context) != VK_SUCCESS) {
    printf("Failed to create instance");
    return 0;
  }

  if (enumerate_physical_devices() != VK_SUCCESS) {
    printf("Failed to query physical devices");
    return 0;
  }

  getPhysicalDeviceProperties(physicalDevice, &props);
  vendorID = props.vendorID;

  destroyInstance(instance, NULL);

  if (vulkan_handle) {
    dlclose(vulkan_handle);
    vulkan_handle = NULL;
  }

  return vendorID;
}

JNIEXPORT jstring JNICALL
Java_com_winlator_cmod_runtime_system_GPUInformation_getRenderer(
    JNIEnv *env, jclass obj, jstring driverName, jobject context) {
  VkPhysicalDeviceProperties props = {};

  if (create_instance(driverName, env, context) != VK_SUCCESS) {
    printf("Failed to create instance");
    return (*env)->NewStringUTF(env, "Unknown");
  }

  if (enumerate_physical_devices() != VK_SUCCESS) {
    printf("Failed to query physical devices");
    return (*env)->NewStringUTF(env, "Unknown");
  }

  getPhysicalDeviceProperties(physicalDevice, &props);
  jstring result = (*env)->NewStringUTF(env, props.deviceName);

  destroyInstance(instance, NULL);

  if (vulkan_handle) {
    dlclose(vulkan_handle);
    vulkan_handle = NULL;
  }

  return result;
}

JNIEXPORT jobjectArray JNICALL
Java_com_winlator_cmod_runtime_system_GPUInformation_enumerateExtensions(
    JNIEnv *env, jclass obj, jstring driverName, jobject context) {
  jobjectArray extensions;
  VkResult result;
  uint32_t extensionCount;
  jclass stringClass = (*env)->FindClass(env, "java/lang/String");

  if (create_instance(driverName, env, context) != VK_SUCCESS) {
    printf("Failed to create instance");
    return (*env)->NewObjectArray(env, 0, stringClass, NULL);
  }

  if (enumerate_physical_devices() != VK_SUCCESS) {
    printf("Failed to query physical devices");
    return (*env)->NewObjectArray(env, 0, stringClass, NULL);
  }

  result = enumerateDeviceExtensionProperties(physicalDevice, NULL,
                                              &extensionCount, NULL);

  if (result != VK_SUCCESS || extensionCount < 1) {
    printf("Failed to query extension count");
    return (*env)->NewObjectArray(env, 0, stringClass, NULL);
  }

  VkExtensionProperties *extensionProperties =
      malloc(sizeof(VkExtensionProperties) * extensionCount);
  if (!extensionProperties) {
    printf("Failed to allocate extension properties");
    return (*env)->NewObjectArray(env, 0, stringClass, NULL);
  }

  result = enumerateDeviceExtensionProperties(
      physicalDevice, NULL, &extensionCount, extensionProperties);

  if (result != VK_SUCCESS) {
    printf("Failed to query extensions (result=%d)", result);
    free(extensionProperties);
    return (*env)->NewObjectArray(env, 0, stringClass, NULL);
  }

  extensions = (jobjectArray)(*env)->NewObjectArray(env, extensionCount,
                                                    stringClass, NULL);
  for (uint32_t i = 0; i < extensionCount; i++) {
    jstring extName =
        (*env)->NewStringUTF(env, extensionProperties[i].extensionName);
    (*env)->SetObjectArrayElement(env, extensions, i, extName);
    (*env)->DeleteLocalRef(env, extName);
  }

  free(extensionProperties);

  destroyInstance(instance, NULL);

  if (vulkan_handle) {
    dlclose(vulkan_handle);
    vulkan_handle = NULL;
  }

  return extensions;
}

JNIEXPORT jstring JNICALL
Java_com_winlator_cmod_runtime_system_GPUInformation_getSystemApiVersion(
    JNIEnv *env, jclass obj) {
    int version = 0;
    char versionStr[16] = {0};

    // 缓存中保存的是十进制整数版本号，命中时直接格式化返回。
    char* content = fileGetContents(APP_CACHE_DIR "/.vk-api-version", NULL, NULL);
    if (content) {
        version = strtol(content, NULL, 10);
        MEMFREE(content);
        if (version > 0) {
            snprintf(versionStr, sizeof(versionStr), "%d.%d.%d",
                     VK_VERSION_MAJOR(version), VK_VERSION_MINOR(version),
                     VK_VERSION_PATCH(version));
            return (*env)->NewStringUTF(env, versionStr);
        }
    }

    VkResult result = VK_SUCCESS;
    VkInstance instance = VK_NULL_HANDLE;

    void* libvulkan = dlopen(LIBVULKAN_PATH, RTLD_NOW | RTLD_LOCAL);
    if (!libvulkan) goto done;

    PFN_vkCreateInstance vkCreateInstance = dlsym(libvulkan, "vkCreateInstance");
    PFN_vkDestroyInstance vkDestroyInstance = dlsym(libvulkan, "vkDestroyInstance");
    PFN_vkEnumeratePhysicalDevices vkEnumeratePhysicalDevices = dlsym(libvulkan, "vkEnumeratePhysicalDevices");
    PFN_vkGetPhysicalDeviceProperties vkGetPhysicalDeviceProperties = dlsym(libvulkan, "vkGetPhysicalDeviceProperties");
    PFN_vkGetPhysicalDeviceFeatures vkGetPhysicalDeviceFeatures = dlsym(libvulkan, "vkGetPhysicalDeviceFeatures");
    // 任一入口缺失都直接放弃，避免后续调用 NULL 函数指针。
    if (!vkCreateInstance || !vkDestroyInstance || !vkEnumeratePhysicalDevices ||
        !vkGetPhysicalDeviceProperties || !vkGetPhysicalDeviceFeatures)
        goto done;

    VkInstanceCreateInfo createInfo = {0};
    createInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;

    result = vkCreateInstance(&createInfo, NULL, &instance);
    if (result != VK_SUCCESS) goto done;

    uint32_t deviceCount = 1;
    VkPhysicalDevice physicalDevice = VK_NULL_HANDLE;
    result = vkEnumeratePhysicalDevices(instance, &deviceCount, &physicalDevice);
    // VK_INCOMPLETE 可接受：数组小于设备数时首个设备仍会被写入。
    if ((result != VK_SUCCESS && result != VK_INCOMPLETE) ||
        physicalDevice == VK_NULL_HANDLE)
        goto done;

    VkPhysicalDeviceProperties properties = {0};
    vkGetPhysicalDeviceProperties(physicalDevice, &properties);

    version = properties.apiVersion;
    if (version >= VK_MAKE_VERSION(1, 3, 0)) {
        VkPhysicalDeviceFeatures features = {0};
        vkGetPhysicalDeviceFeatures(physicalDevice, &features);
        // requiredFeatures 标记了必须为 VK_TRUE 的关键特性；任一缺失即降级报告 1.2。
        static const VkPhysicalDeviceFeatures requiredFeatures = {VK_TRUE, VK_TRUE, VK_TRUE, VK_TRUE, VK_TRUE, VK_TRUE, VK_TRUE, VK_TRUE, VK_TRUE, VK_TRUE, VK_TRUE, VK_TRUE, VK_TRUE, VK_TRUE, VK_TRUE, VK_TRUE, VK_TRUE, VK_TRUE, VK_TRUE, VK_TRUE, VK_TRUE, VK_TRUE, VK_TRUE, VK_TRUE, VK_TRUE, VK_TRUE, VK_TRUE, VK_TRUE, VK_TRUE, VK_TRUE, VK_FALSE, VK_TRUE, VK_TRUE, VK_TRUE, VK_TRUE, VK_TRUE, VK_TRUE, VK_TRUE, VK_TRUE, VK_FALSE, VK_TRUE, VK_TRUE, VK_FALSE, VK_FALSE, VK_TRUE, VK_TRUE, VK_TRUE, VK_FALSE, VK_TRUE, VK_TRUE, VK_TRUE, VK_TRUE, VK_FALSE, VK_TRUE, VK_TRUE};
        // VkPhysicalDeviceFeatures 仅由 VkBool32 组成、无 padding，可直接 memcmp。
        if (memcmp(&features, &requiredFeatures, sizeof(requiredFeatures)) != 0) {
            version = VK_MAKE_VERSION(1, 2, 0);
        }
    }

    char value[32] = {0};
    snprintf(value, sizeof(value), "%d", version);
    filePutContents(APP_CACHE_DIR "/.vk-api-version", value, strlen(value));

done:
    if (instance) vkDestroyInstance(instance, NULL);
    if (libvulkan) dlclose(libvulkan);
    if (version == 0) return NULL;
    snprintf(versionStr, sizeof(versionStr), "%d.%d.%d",
             VK_VERSION_MAJOR(version), VK_VERSION_MINOR(version),
             VK_VERSION_PATCH(version));
    return (*env)->NewStringUTF(env, versionStr);
}

JNIEXPORT jstring JNICALL
Java_com_winlator_cmod_runtime_system_GPUInformation_getSystemDriverVersion(
    JNIEnv *env, jclass obj) {
    void *libvulkan = NULL;
    VkInstance instance = VK_NULL_HANDLE;
    jstring result = NULL;
    char versionStr[33];

    PFN_vkCreateInstance vkCreateInstance = NULL;
    PFN_vkDestroyInstance vkDestroyInstance = NULL;
    PFN_vkEnumeratePhysicalDevices vkEnumeratePhysicalDevices = NULL;
    PFN_vkGetPhysicalDeviceProperties vkGetPhysicalDeviceProperties = NULL;

    // Load the system Vulkan driver. winlator_open_system_vulkan() also
    // preloads vendor ICD deps so vkGetPhysicalDeviceProperties can report the
    // real driver (same pattern as getSystemApiVersion).
    libvulkan = winlator_open_system_vulkan();
    if (!libvulkan) goto done;

    vkCreateInstance =
        (PFN_vkCreateInstance)dlsym(libvulkan, "vkCreateInstance");
    vkDestroyInstance =
        (PFN_vkDestroyInstance)dlsym(libvulkan, "vkDestroyInstance");
    vkEnumeratePhysicalDevices = (PFN_vkEnumeratePhysicalDevices)dlsym(
        libvulkan, "vkEnumeratePhysicalDevices");
    vkGetPhysicalDeviceProperties = (PFN_vkGetPhysicalDeviceProperties)dlsym(
        libvulkan, "vkGetPhysicalDeviceProperties");
    if (!vkCreateInstance || !vkDestroyInstance || !vkEnumeratePhysicalDevices ||
        !vkGetPhysicalDeviceProperties)
        goto done;

    VkInstanceCreateInfo createInfo = {0};
    createInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;

    VkResult res = vkCreateInstance(&createInfo, NULL, &instance);
    if (res != VK_SUCCESS || instance == VK_NULL_HANDLE) goto done;

    uint32_t deviceCount = 0;
    res = vkEnumeratePhysicalDevices(instance, &deviceCount, NULL);
    if (res != VK_SUCCESS || deviceCount == 0) goto done;

    VkPhysicalDevice physicalDevice = VK_NULL_HANDLE;
    deviceCount = 1;
    res = vkEnumeratePhysicalDevices(instance, &deviceCount, &physicalDevice);
    // VK_INCOMPLETE is fine: the array is smaller than the device count, but
    // the first device is still written.
    if ((res != VK_SUCCESS && res != VK_INCOMPLETE) ||
        physicalDevice == VK_NULL_HANDLE)
        goto done;

    VkPhysicalDeviceProperties properties = {0};
    vkGetPhysicalDeviceProperties(physicalDevice, &properties);

    // 3x uint32 (10 digits max each) + 2 dots + NUL = 33 bytes.
    snprintf(versionStr, sizeof(versionStr), "%d.%d.%d",
             VK_VERSION_MAJOR(properties.driverVersion),
             VK_VERSION_MINOR(properties.driverVersion),
             VK_VERSION_PATCH(properties.driverVersion));

    result = (*env)->NewStringUTF(env, versionStr);

done:
    if (instance) vkDestroyInstance(instance, NULL);
    if (libvulkan) dlclose(libvulkan);
    return result;
}
