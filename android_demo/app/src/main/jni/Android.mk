LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES := ml_dmlc_tvm_native_c_api.cc

ifndef TVM_HOME
$(error TVM_HOME not set)
endif

LOCAL_C_INCLUDES := $(TVM_HOME)/include \
                    $(TVM_HOME)/3rdparty/dlpack/include \
                    $(TVM_HOME)/3rdparty/dmlc-core/include \
                    $(TVM_HOME)/3rdparty/HalideIR/src \
                    $(TVM_HOME)/topi/include

LOCAL_MODULE := tvm4j_runtime_packed

LOCAL_CPP_FEATURES += exceptions
LOCAL_LDLIBS += -latomic
LOCAL_LDFLAGS := -llog
LOCAL_ARM_MODE := arm

include $(BUILD_SHARED_LIBRARY)