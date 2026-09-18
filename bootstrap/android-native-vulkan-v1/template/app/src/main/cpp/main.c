#define VK_USE_PLATFORM_ANDROID_KHR 1

#include <android/log.h>
#include <android_native_app_glue.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <vulkan/vulkan.h>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "ClawVulkan", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "ClawVulkan", __VA_ARGS__)

typedef struct Renderer {
  VkInstance instance;
  VkSurfaceKHR surface;
  VkPhysicalDevice physical_device;
  VkDevice device;
  uint32_t queue_family;
  VkQueue queue;
  VkSwapchainKHR swapchain;
  VkFormat format;
  VkExtent2D extent;
  uint32_t image_count;
  VkImage *images;
  VkCommandPool command_pool;
  VkCommandBuffer *commands;
  VkSemaphore acquired;
  bool ready;
  uint64_t frame;
} Renderer;

static bool vk_ok(VkResult result, const char *operation) {
  if (result == VK_SUCCESS) return true;
  LOGE("%s failed: %d", operation, result);
  return false;
}

static void renderer_destroy(Renderer *renderer) {
  if (renderer->device != VK_NULL_HANDLE) vkDeviceWaitIdle(renderer->device);
  if (renderer->device != VK_NULL_HANDLE && renderer->acquired != VK_NULL_HANDLE)
    vkDestroySemaphore(renderer->device, renderer->acquired, NULL);
  if (renderer->device != VK_NULL_HANDLE && renderer->command_pool != VK_NULL_HANDLE)
    vkDestroyCommandPool(renderer->device, renderer->command_pool, NULL);
  free(renderer->commands);
  free(renderer->images);
  if (renderer->device != VK_NULL_HANDLE && renderer->swapchain != VK_NULL_HANDLE)
    vkDestroySwapchainKHR(renderer->device, renderer->swapchain, NULL);
  if (renderer->device != VK_NULL_HANDLE) vkDestroyDevice(renderer->device, NULL);
  if (renderer->instance != VK_NULL_HANDLE && renderer->surface != VK_NULL_HANDLE)
    vkDestroySurfaceKHR(renderer->instance, renderer->surface, NULL);
  if (renderer->instance != VK_NULL_HANDLE) vkDestroyInstance(renderer->instance, NULL);
  memset(renderer, 0, sizeof(*renderer));
}

static bool create_instance(Renderer *renderer, ANativeWindow *window) {
  const char *extensions[] = {"VK_KHR_surface", "VK_KHR_android_surface"};
  VkApplicationInfo application = {
      .sType = VK_STRUCTURE_TYPE_APPLICATION_INFO,
      .pApplicationName = "ClawInOne Vulkan Starter",
      .applicationVersion = VK_MAKE_VERSION(1, 0, 0),
      .pEngineName = "ClawInOne",
      .engineVersion = VK_MAKE_VERSION(1, 0, 0),
      .apiVersion = VK_API_VERSION_1_1,
  };
  VkInstanceCreateInfo create_info = {
      .sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO,
      .pApplicationInfo = &application,
      .enabledExtensionCount = 2,
      .ppEnabledExtensionNames = extensions,
  };
  if (!vk_ok(vkCreateInstance(&create_info, NULL, &renderer->instance), "vkCreateInstance"))
    return false;
  VkAndroidSurfaceCreateInfoKHR surface_info = {
      .sType = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR,
      .window = window,
  };
  return vk_ok(vkCreateAndroidSurfaceKHR(renderer->instance, &surface_info, NULL,
                                         &renderer->surface),
               "vkCreateAndroidSurfaceKHR");
}

static bool select_device(Renderer *renderer) {
  uint32_t physical_count = 0;
  if (!vk_ok(vkEnumeratePhysicalDevices(renderer->instance, &physical_count, NULL),
             "vkEnumeratePhysicalDevices") ||
      physical_count == 0)
    return false;
  VkPhysicalDevice *devices = calloc(physical_count, sizeof(*devices));
  if (devices == NULL) return false;
  vkEnumeratePhysicalDevices(renderer->instance, &physical_count, devices);
  bool found = false;
  for (uint32_t device_index = 0; device_index < physical_count && !found; ++device_index) {
    uint32_t family_count = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(devices[device_index], &family_count, NULL);
    VkQueueFamilyProperties *families = calloc(family_count, sizeof(*families));
    if (families == NULL) continue;
    vkGetPhysicalDeviceQueueFamilyProperties(devices[device_index], &family_count, families);
    for (uint32_t family = 0; family < family_count; ++family) {
      VkBool32 present = VK_FALSE;
      vkGetPhysicalDeviceSurfaceSupportKHR(devices[device_index], family, renderer->surface,
                                           &present);
      if ((families[family].queueFlags & VK_QUEUE_GRAPHICS_BIT) != 0 && present == VK_TRUE) {
        renderer->physical_device = devices[device_index];
        renderer->queue_family = family;
        found = true;
        break;
      }
    }
    free(families);
  }
  free(devices);
  if (!found) return false;

  float priority = 1.0f;
  VkDeviceQueueCreateInfo queue_info = {
      .sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO,
      .queueFamilyIndex = renderer->queue_family,
      .queueCount = 1,
      .pQueuePriorities = &priority,
  };
  const char *extensions[] = {VK_KHR_SWAPCHAIN_EXTENSION_NAME};
  VkDeviceCreateInfo device_info = {
      .sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO,
      .queueCreateInfoCount = 1,
      .pQueueCreateInfos = &queue_info,
      .enabledExtensionCount = 1,
      .ppEnabledExtensionNames = extensions,
  };
  if (!vk_ok(vkCreateDevice(renderer->physical_device, &device_info, NULL, &renderer->device),
             "vkCreateDevice"))
    return false;
  vkGetDeviceQueue(renderer->device, renderer->queue_family, 0, &renderer->queue);
  return true;
}

static bool create_swapchain(Renderer *renderer, ANativeWindow *window) {
  VkSurfaceCapabilitiesKHR capabilities;
  if (!vk_ok(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(renderer->physical_device,
                                                        renderer->surface, &capabilities),
             "vkGetPhysicalDeviceSurfaceCapabilitiesKHR"))
    return false;
  uint32_t format_count = 0;
  vkGetPhysicalDeviceSurfaceFormatsKHR(renderer->physical_device, renderer->surface,
                                       &format_count, NULL);
  if (format_count == 0) return false;
  VkSurfaceFormatKHR *formats = calloc(format_count, sizeof(*formats));
  if (formats == NULL) return false;
  vkGetPhysicalDeviceSurfaceFormatsKHR(renderer->physical_device, renderer->surface,
                                       &format_count, formats);
  VkSurfaceFormatKHR chosen = formats[0];
  for (uint32_t index = 0; index < format_count; ++index) {
    if (formats[index].format == VK_FORMAT_R8G8B8A8_UNORM ||
        formats[index].format == VK_FORMAT_B8G8R8A8_UNORM) {
      chosen = formats[index];
      break;
    }
  }
  free(formats);
  renderer->format = chosen.format;
  renderer->extent = capabilities.currentExtent;
  if (renderer->extent.width == UINT32_MAX) {
    renderer->extent.width = (uint32_t)ANativeWindow_getWidth(window);
    renderer->extent.height = (uint32_t)ANativeWindow_getHeight(window);
  }
  uint32_t image_count = capabilities.minImageCount + 1;
  if (capabilities.maxImageCount != 0 && image_count > capabilities.maxImageCount)
    image_count = capabilities.maxImageCount;
  VkSwapchainCreateInfoKHR swapchain_info = {
      .sType = VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR,
      .surface = renderer->surface,
      .minImageCount = image_count,
      .imageFormat = renderer->format,
      .imageColorSpace = chosen.colorSpace,
      .imageExtent = renderer->extent,
      .imageArrayLayers = 1,
      .imageUsage = VK_IMAGE_USAGE_TRANSFER_DST_BIT,
      .imageSharingMode = VK_SHARING_MODE_EXCLUSIVE,
      .preTransform = capabilities.currentTransform,
      .compositeAlpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR,
      .presentMode = VK_PRESENT_MODE_FIFO_KHR,
      .clipped = VK_TRUE,
  };
  if (!vk_ok(vkCreateSwapchainKHR(renderer->device, &swapchain_info, NULL,
                                  &renderer->swapchain),
             "vkCreateSwapchainKHR"))
    return false;
  vkGetSwapchainImagesKHR(renderer->device, renderer->swapchain, &renderer->image_count, NULL);
  renderer->images = calloc(renderer->image_count, sizeof(*renderer->images));
  renderer->commands = calloc(renderer->image_count, sizeof(*renderer->commands));
  if (renderer->images == NULL || renderer->commands == NULL) return false;
  vkGetSwapchainImagesKHR(renderer->device, renderer->swapchain, &renderer->image_count,
                          renderer->images);

  VkCommandPoolCreateInfo pool_info = {
      .sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO,
      .flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT,
      .queueFamilyIndex = renderer->queue_family,
  };
  if (!vk_ok(vkCreateCommandPool(renderer->device, &pool_info, NULL,
                                 &renderer->command_pool),
             "vkCreateCommandPool"))
    return false;
  VkCommandBufferAllocateInfo allocation = {
      .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO,
      .commandPool = renderer->command_pool,
      .level = VK_COMMAND_BUFFER_LEVEL_PRIMARY,
      .commandBufferCount = renderer->image_count,
  };
  if (!vk_ok(vkAllocateCommandBuffers(renderer->device, &allocation, renderer->commands),
             "vkAllocateCommandBuffers"))
    return false;
  VkSemaphoreCreateInfo semaphore_info = {.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
  return vk_ok(vkCreateSemaphore(renderer->device, &semaphore_info, NULL, &renderer->acquired),
               "vkCreateSemaphore");
}

static bool renderer_create(Renderer *renderer, ANativeWindow *window) {
  memset(renderer, 0, sizeof(*renderer));
  if (!create_instance(renderer, window) || !select_device(renderer) ||
      !create_swapchain(renderer, window)) {
    renderer_destroy(renderer);
    return false;
  }
  renderer->ready = true;
  LOGI("CLAW_NATIVE_VULKAN_READY %ux%u images=%u", renderer->extent.width,
       renderer->extent.height, renderer->image_count);
  return true;
}

static bool renderer_draw(Renderer *renderer) {
  uint32_t image_index = 0;
  VkResult acquired = vkAcquireNextImageKHR(renderer->device, renderer->swapchain, UINT64_MAX,
                                             renderer->acquired, VK_NULL_HANDLE, &image_index);
  if (acquired == VK_ERROR_OUT_OF_DATE_KHR) return false;
  if (acquired != VK_SUCCESS && acquired != VK_SUBOPTIMAL_KHR) return false;
  VkCommandBuffer command = renderer->commands[image_index];
  vkResetCommandBuffer(command, 0);
  VkCommandBufferBeginInfo begin = {.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,
                                    .flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT};
  vkBeginCommandBuffer(command, &begin);
  VkImageMemoryBarrier to_transfer = {
      .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
      .srcAccessMask = 0,
      .dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT,
      .oldLayout = VK_IMAGE_LAYOUT_UNDEFINED,
      .newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
      .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
      .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
      .image = renderer->images[image_index],
      .subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1},
  };
  vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                       VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, NULL, 0, NULL, 1, &to_transfer);
  float pulse = 0.08f + (float)(renderer->frame % 180) / 1800.0f;
  VkClearColorValue color = {.float32 = {0.02f, 0.18f + pulse, 0.42f + pulse, 1.0f}};
  VkImageSubresourceRange range = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
  vkCmdClearColorImage(command, renderer->images[image_index],
                       VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, &color, 1, &range);
  VkImageMemoryBarrier to_present = to_transfer;
  to_present.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
  to_present.dstAccessMask = 0;
  to_present.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
  to_present.newLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
  vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_TRANSFER_BIT,
                       VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, 0, NULL, 0, NULL, 1, &to_present);
  vkEndCommandBuffer(command);
  VkPipelineStageFlags wait_stage = VK_PIPELINE_STAGE_TRANSFER_BIT;
  VkSubmitInfo submit = {
      .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO,
      .waitSemaphoreCount = 1,
      .pWaitSemaphores = &renderer->acquired,
      .pWaitDstStageMask = &wait_stage,
      .commandBufferCount = 1,
      .pCommandBuffers = &command,
  };
  if (!vk_ok(vkQueueSubmit(renderer->queue, 1, &submit, VK_NULL_HANDLE), "vkQueueSubmit"))
    return false;
  VkPresentInfoKHR present = {
      .sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR,
      .swapchainCount = 1,
      .pSwapchains = &renderer->swapchain,
      .pImageIndices = &image_index,
  };
  VkResult result = vkQueuePresentKHR(renderer->queue, &present);
  vkQueueWaitIdle(renderer->queue);
  renderer->frame += 1;
  return result == VK_SUCCESS || result == VK_SUBOPTIMAL_KHR;
}

void android_main(struct android_app *app) {
  Renderer renderer = {0};
  while (!app->destroyRequested) {
    int events = 0;
    struct android_poll_source *source = NULL;
    int timeout = renderer.ready ? 16 : -1;
    int identifier = ALooper_pollOnce(timeout, NULL, &events, (void **)&source);
    if (identifier >= 0 && source != NULL) source->process(app, source);
    if (app->window != NULL && !renderer.ready) renderer_create(&renderer, app->window);
    if (app->window == NULL && renderer.ready) renderer_destroy(&renderer);
    if (renderer.ready && !renderer_draw(&renderer)) renderer_destroy(&renderer);
  }
  renderer_destroy(&renderer);
}
