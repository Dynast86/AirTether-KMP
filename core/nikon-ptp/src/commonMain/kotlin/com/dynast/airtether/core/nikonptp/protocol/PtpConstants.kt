package com.dynast.airtether.core.nikonptp.protocol

/**
 * Standard ISO 15740 PTP & Nikon Vendor-Specific (MTP/PTP) protocol constants for Z 6II / Zf series.
 */
object PtpConstants {

    // TCP/IP Port for Wireless PTP/IP (Command & Event dual-socket channels)
    const val PTP_IP_DEFAULT_PORT: Int = 15740

    // Standard USB / Container Header Size (Length[4] + Type[2] + Code[2] + TransactionId[4])
    const val PTP_CONTAINER_HEADER_SIZE: Int = 12

    // Nikon Z-series (Z 6II / Zf) GetLiveViewImage (0x9203) fixed binary header size before JPEG stream
    const val NIKON_LIVE_VIEW_HEADER_SIZE: Int = 384

    // Container Types (Little-Endian UShort)
    const val CONTAINER_UNDEFINED: UShort = 0x0000u
    const val CONTAINER_COMMAND: UShort = 0x0001u
    const val CONTAINER_DATA: UShort = 0x0002u
    const val CONTAINER_RESPONSE: UShort = 0x0003u
    const val CONTAINER_EVENT: UShort = 0x0004u

    // Standard PTP OpCodes
    const val OP_GET_DEVICE_INFO: UShort = 0x1001u
    const val OP_OPEN_SESSION: UShort = 0x1002u
    const val OP_CLOSE_SESSION: UShort = 0x1003u
    const val OP_GET_STORAGE_IDS: UShort = 0x1004u
    const val OP_GET_STORAGE_INFO: UShort = 0x1005u
    const val OP_GET_OBJECT_HANDLES: UShort = 0x1007u
    const val OP_GET_OBJECT_INFO: UShort = 0x1008u
    const val OP_GET_OBJECT: UShort = 0x1009u
    const val OP_GET_THUMB: UShort = 0x100Au
    const val OP_INITIATE_CAPTURE: UShort = 0x100Eu
    const val OP_GET_DEVICE_PROP_DESC: UShort = 0x1014u
    const val OP_GET_DEVICE_PROP_VALUE: UShort = 0x1015u
    const val OP_SET_DEVICE_PROP_VALUE: UShort = 0x1016u
    const val OP_GET_PARTIAL_OBJECT: UShort = 0x101Bu

    // Nikon Vendor-Specific OpCodes
    const val OP_NIKON_INITIATE_CAPTURE_REC_IN_SDRAM: UShort = 0x90C0u
    const val OP_NIKON_GET_EVENT: UShort = 0x90C7u
    const val OP_NIKON_DEVICE_READY: UShort = 0x90C8u
    const val OP_NIKON_START_LIVE_VIEW: UShort = 0x9201u
    const val OP_NIKON_END_LIVE_VIEW: UShort = 0x9202u
    const val OP_NIKON_GET_LIVE_VIEW_IMAGE: UShort = 0x9203u
    const val OP_NIKON_MF_DRIVE: UShort = 0x9204u
    const val OP_NIKON_SET_TOUCH_AF_AREA: UShort = 0x9205u
    const val OP_NIKON_INITIATE_CAPTURE_REC_IN_MEDIA: UShort = 0x9207u

    // Standard & Nikon Response Codes
    const val RES_OK: UShort = 0x2001u
    const val RES_GENERAL_ERROR: UShort = 0x2002u
    const val RES_SESSION_NOT_OPEN: UShort = 0x2003u
    const val RES_INVALID_TRANSACTION_ID: UShort = 0x2004u
    const val RES_OPERATION_NOT_SUPPORTED: UShort = 0x2005u
    const val RES_PARAMETER_NOT_SUPPORTED: UShort = 0x2006u
    const val RES_INCOMPLETE_TRANSFER: UShort = 0x2007u
    const val RES_DEVICE_BUSY: UShort = 0x2019u
    const val RES_SESSION_ALREADY_OPEN: UShort = 0x201Eu
    const val RES_NIKON_NOT_LIVE_VIEW: UShort = 0xA00Bu

    // Standard & Nikon Event Codes
    const val EVT_CANCEL_TRANSACTION: UShort = 0x4001u
    const val EVT_OBJECT_ADDED: UShort = 0x4002u
    const val EVT_OBJECT_REMOVED: UShort = 0x4003u
    const val EVT_STORE_ADDED: UShort = 0x4004u
    const val EVT_DEVICE_PROP_CHANGED: UShort = 0x4006u
    const val EVT_OBJECT_INFO_CHANGED: UShort = 0x4007u
    const val EVT_CAPTURE_COMPLETE: UShort = 0x400Du
    const val EVT_NIKON_OBJECT_ADDED_IN_SDRAM: UShort = 0xC101u
    const val EVT_NIKON_CAPTURE_COMPLETE_REC_IN_SDRAM: UShort = 0xC102u

    // Device Property Codes
    const val PROP_BATTERY_LEVEL: UShort = 0x5001u
    const val PROP_WHITE_BALANCE: UShort = 0x5005u
    const val PROP_F_NUMBER: UShort = 0x5007u
    const val PROP_FOCAL_LENGTH: UShort = 0x5008u
    const val PROP_EXPOSURE_PROGRAM_MODE: UShort = 0x500Eu
    const val PROP_EXPOSURE_INDEX_ISO: UShort = 0x500Fu
    const val PROP_EXPOSURE_TIME: UShort = 0x500Du
    const val PROP_EXPOSURE_BIAS_COMPENSATION: UShort = 0x5010u

    // Nikon Vendor-Specific Movie Recording Frame Rate / Quality Property Codes
    const val PROP_NIKON_MOVIE_REC_QUALITY: UShort = 0xD1A8u
    const val PROP_NIKON_MOVIE_FRAME_RATE: UShort = 0xD1A9u
}
