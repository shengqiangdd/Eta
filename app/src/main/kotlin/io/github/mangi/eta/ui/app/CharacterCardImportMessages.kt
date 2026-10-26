package io.github.mangi.eta.ui.app

import android.content.res.Resources
import io.github.mangi.eta.R

/** 导入只公开受控分类，文件内容与底层异常文本不进入界面。 */
internal fun characterCardImportMessage(resources: Resources, code: String?): String? =
    characterCardImportMessage(code) { resources.getString(it) }

/** 纯逻辑版本：文案由调用方解析，单测不需要 Android Resources。 */
internal fun characterCardImportMessage(code: String?, text: (Int) -> String): String? = when (code) {
    "CARD_V3_INVALID" -> text(R.string.character_import_error_v3)
    "CARD_METADATA_MISSING" -> text(R.string.character_import_error_metadata)
    "CARD_INVALID_JSON" -> text(R.string.character_import_error_json)
    "CARD_TOO_LARGE" -> text(R.string.character_import_error_too_large)
    "CARD_INVALID_UTF8" -> text(R.string.character_import_error_utf8)
    "CARD_UNSUPPORTED_SPEC" -> text(R.string.character_import_error_spec)
    "CARD_INVALID_PNG" -> text(R.string.character_import_error_png)
    "CARD_INVALID_DATA" -> text(R.string.character_import_error_data)
    else -> null
}
