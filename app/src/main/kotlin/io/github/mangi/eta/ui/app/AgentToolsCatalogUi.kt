package io.github.mangi.eta.ui.app

import android.content.Context
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.model.AgentToolsUiState
import io.github.mangi.eta.ui.model.ToolGroupUi
import io.github.mangi.eta.ui.model.ToolItemUi

internal fun buildToolsState(context: Context): AgentToolsUiState =
    buildToolsState { context.getString(it) }

/**
 * 文案来源可注入：纯 JVM 单测传一个 `{ "res:$it" }` 就能验证分组与元数据，
 * 不必为此拉起 Robolectric —— 本机 aarch64 上没有 sdk 36 的运行时，
 * 那条用例等于从来没跑过（§32.45）。生产路径仍走上面的 Context 重载。
 */
internal fun buildToolsState(text: (Int) -> String): AgentToolsUiState =
    AgentToolsUiState(
        groups = listOf(
            ToolGroupUi(
                id = "screen",
                title = text(R.string.state_screens_and_controls_3f095b),
                tools = listOf(
                    ToolItemUi("observe_screen", text(R.string.tool_ui_watch_the_screen_e70f2a), text(R.string.tool_ui_read_the_current_node_and_attach_the_original_im_df1fec)),
                    ToolItemUi("present_to_user", text(R.string.tool_present_to_user), text(R.string.tool_present_to_user_description)),
                    ToolItemUi("release_gui", text(R.string.tool_release_gui), text(R.string.tool_release_gui_description)),
                    ToolItemUi("tap_element", text(R.string.tool_ui_click_element_7a3d91), text(R.string.tool_ui_click_on_the_most_recently_observed_node_b4cf5a)),
                    ToolItemUi("tap_area", text(R.string.tool_ui_click_area_cbaa08), text(R.string.tool_ui_click_by_coordinate_area_2ad961)),
                    ToolItemUi("long_press", text(R.string.tool_ui_long_press_f7a417), text(R.string.tool_ui_long_press_on_coordinates_or_elements_796384)),
                    ToolItemUi("swipe", text(R.string.tool_ui_slide_3723aa), text(R.string.tool_ui_perform_up_down_left_and_right_swipe_gestures_3ef0de)),
                    ToolItemUi("scroll", text(R.string.tool_ui_scroll_220e68), text(R.string.tool_ui_scroll_the_page_or_specify_a_node_83ab24)),
                ),
            ),
            ToolGroupUi(
                id = "text",
                title = text(R.string.state_text_and_clipboard_3a7340),
                tools = listOf(
<<<<<<< HEAD
                    ToolItemUi("type_text", text(R.string.tool_ui_enter_text_ae47ab), text(R.string.tool_ui_type_text_summary)),
                    ToolItemUi("wait_for_text", text(R.string.tool_ui_wait_for_text_9e9a54), text(R.string.tool_ui_wait_for_the_specified_text_to_appear_on_the_scr_43f9b0)),
=======
                    ToolItemUi("type_text", context.getString(R.string.tool_ui_enter_text_ae47ab), context.getString(R.string.tool_ui_type_text_summary)),
                    ToolItemUi("wait_for_text", context.getString(R.string.tool_ui_wait_for_text_9e9a54), context.getString(R.string.tool_ui_wait_for_the_specified_text_to_appear_on_the_scr_43f9b0)),
                    ToolItemUi("set_clipboard", "写入剪贴板", "将文本写入系统剪贴板，便于后续粘贴长文本或特殊字符。"),
                    ToolItemUi("get_clipboard", "读取剪贴板", "读取系统剪贴板中的当前文本内容。"),
>>>>>>> origin/main
                ),
            ),
            ToolGroupUi(
                id = "web",
                title = text(R.string.state_web_browsing_e56105),
                tools = listOf(
<<<<<<< HEAD
                    ToolItemUi("web_search", text(R.string.tool_web_search), text(R.string.tool_web_search_description)),
                    ToolItemUi("fetch_url", text(R.string.tool_fetch_url), text(R.string.tool_fetch_url_description)),
                    ToolItemUi("browser_use", text(R.string.tool_ui_agent_browser_a66bd5), text(R.string.tool_ui_open_web_pages_off_screen_and_keep_a_takeover_br_72972e)),
                    ToolItemUi("browser_read", text(R.string.tool_ui_read_web_pages_4f0bb9), text(R.string.tool_ui_extract_rendered_text_lists_and_links_8bdcdd)),
                    ToolItemUi("browser_interact", text(R.string.tool_ui_web_page_interaction_331b3f), text(R.string.tool_ui_find_click_and_enter_page_elements_8f102d)),
                    ToolItemUi("browser_screenshot", text(R.string.tool_ui_page_screenshot_c823a2), text(R.string.tool_ui_give_the_current_web_page_viewport_to_the_visual_f62274)),
=======
                    ToolItemUi("web_search", context.getString(R.string.tool_web_search), context.getString(R.string.tool_web_search_description)),
                    ToolItemUi("fetch_url", context.getString(R.string.tool_fetch_url), context.getString(R.string.tool_fetch_url_description)),
                    ToolItemUi("browser_use", context.getString(R.string.tool_ui_agent_browser_a66bd5), context.getString(R.string.tool_ui_open_web_pages_off_screen_and_keep_a_takeover_br_72972e)),
>>>>>>> origin/main
                ),
            ),
            ToolGroupUi(
                id = "app",
                title = text(R.string.state_applications_and_systems_9624e6),
                tools = listOf(
                    ToolItemUi("search_apps", text(R.string.tool_ui_search_apps_897fdf), text(R.string.tool_ui_query_installed_applications_by_name_or_package__32b004)),
                    ToolItemUi("get_current_context", text(R.string.tool_ui_time_and_location_693893), text(R.string.tool_ui_read_system_time_and_recent_location_b9f4ae)),
                    ToolItemUi("launch_app", text(R.string.tool_ui_open_app_7c65e7), text(R.string.tool_ui_start_the_specified_package_name_or_application__beabff)),
                    ToolItemUi("open_uri", text(R.string.tool_ui_open_with_app_32c24e), text(R.string.tool_ui_explicitly_hand_over_links_or_deep_links_to_exte_35ff26)),
                    ToolItemUi("press_key", text(R.string.tool_ui_button_02eafa), text(R.string.tool_ui_system_buttons_such_as_return_homepage_recent_ta_1b4cf0)),
                    ToolItemUi("open_system_panel", text(R.string.tool_ui_system_panel_b0f7a3), text(R.string.tool_ui_open_the_notification_bar_quick_settings_and_oth_5e51cf)),
                ),
            ),
            ToolGroupUi(
                id = "device_direct",
                title = text(R.string.state_direct_access_to_equipment_eda92c),
                tools = io.github.mangi.eta.agent.model.AgentPhoneToolCatalog.entries.filterNot { it.personal }.map {
                    ToolItemUi(it.name, it.title, it.description)
                } + listOf(
                    ToolItemUi("list_alarms", text(R.string.tool_ui_alarm_clock_schedule_acae32), text(R.string.tool_ui_read_the_alarm_clock_that_has_been_created_in_th_2320d6)),
                    ToolItemUi("list_active_timers", text(R.string.tool_ui_activity_timer_36f107), text(R.string.tool_ui_read_running_or_paused_timers_3437c8)),
                    ToolItemUi("get_setting", text(R.string.tool_ui_read_system_settings_d455ce), text(R.string.tool_ui_read_the_specified_settings_key_496975)),
                    ToolItemUi("set_setting", text(R.string.tool_ui_modify_system_settings_ae1f4c), text(R.string.tool_ui_modify_android_settings_keys_91a37e)),
                    ToolItemUi("set_device_state", text(R.string.tool_ui_network_switch_834347), text(R.string.tool_ui_directly_control_wi_fi_or_bluetooth_4fa0b9)),
                    ToolItemUi("app_state_control", text(R.string.tool_ui_application_status_930ff0), text(R.string.tool_ui_stop_freeze_or_unfreeze_apps_a27438)),
                    ToolItemUi("get_logcat", text(R.string.tool_ui_system_log_096733), text(R.string.tool_ui_bounded_reading_and_filtering_of_recent_logs_0a268a)),
                    ToolItemUi("set_alarm", text(R.string.tool_ui_set_alarm_25ca3c), text(R.string.tool_ui_create_a_system_alarm_directly_and_open_the_cloc_9aa214)),
                    ToolItemUi("set_timer", text(R.string.tool_ui_set_timer_aee60c), text(R.string.tool_ui_directly_create_system_timers_up_to_24_hours_87c476)),
                    ToolItemUi("inspect_app", text(R.string.tool_inspect_app), text(R.string.tool_inspect_app_description)),
                    ToolItemUi("device_status", text(R.string.tool_ui_device_status_567a4c), text(R.string.tool_ui_read_power_memory_storage_and_system_version_c501d5)),
                    ToolItemUi("network_info", text(R.string.tool_ui_network_status_6bd556), text(R.string.tool_ui_read_networking_method_and_current_wi_fi_status_68016a)),
                    ToolItemUi("media_control", text(R.string.tool_ui_media_control_585edc), text(R.string.tool_ui_play_pause_and_switch_songs_without_operating_th_311cb8)),
                    ToolItemUi("set_volume", text(R.string.tool_ui_set_volume_85a691), text(R.string.tool_ui_set_by_media_alarm_clock_ringtone_and_other_chan_3fcc3e)),
                    ToolItemUi("top_memory_apps", text(R.string.tool_ui_memory_ranking_408ca1), text(R.string.tool_ui_view_the_currently_most_occupied_processes_8646c4)),
                    ToolItemUi("top_storage_apps", text(R.string.tool_ui_storage_ranking_86a16c), text(R.string.tool_ui_check_application_data_and_cache_usage_837e9f)),
                ),
            ),
            ToolGroupUi(
                id = "personal_data",
                title = text(R.string.state_direct_access_to_personal_data_387d7b),
                tools = io.github.mangi.eta.agent.context.PersonalSearchTools.searches.map {
                    ToolItemUi(it.name, it.title, it.description)
                } + io.github.mangi.eta.agent.model.AgentPhoneToolCatalog.entries.filter { it.personal }.map {
                    ToolItemUi(it.name, it.title, it.description)
                } + listOf(
                    ToolItemUi("read_personal_item", text(R.string.tool_ui_read_personal_item), text(R.string.tool_ui_read_personal_item_summary)),
                    ToolItemUi("summarize_bills", text(R.string.tool_ui_summarize_bills), text(R.string.tool_ui_summarize_bills_summary)),
                    ToolItemUi("read_sms_code", text(R.string.tool_ui_read_verification_code_7d1121), text(R.string.tool_ui_only_extract_verification_codes_from_recent_sms__0fb8c1)),
                    ToolItemUi("recent_notifications", text(R.string.tool_ui_read_notification_7fdc09), text(R.string.tool_ui_read_the_current_notification_title_and_text_0faee7)),
                    ToolItemUi("search_notification_history", text(R.string.tool_ui_notification_history_95d015), text(R.string.tool_ui_retrieve_the_last_7_days_of_notifications_saved__643e43)),
                    ToolItemUi("recent_app_activity", text(R.string.tool_ui_recently_applied_08f74c), text(R.string.tool_ui_view_recently_opened_apps_and_times_bf9d50)),
                    ToolItemUi("app_usage_summary", text(R.string.tool_ui_app_usage_statistics_ee20d3), text(R.string.tool_ui_summarize_recent_app_usage_by_foreground_duratio_b346c8)),
                    ToolItemUi("get_current_location", text(R.string.tool_ui_current_location_b458ea), text(R.string.tool_ui_read_the_closest_location_the_system_already_has_255a6c)),
                    ToolItemUi("get_device_environment", text(R.string.tool_ui_equipment_environment_1026ec), text(R.string.tool_ui_read_lock_screen_do_not_disturb_audio_output_and_9260b8)),
                    ToolItemUi("search_clipboard_history", text(R.string.tool_ui_clipboard_history_b377bb), text(R.string.tool_ui_retrieve_clipboard_contents_saved_by_system_inpu_1dc9db)),
                    ToolItemUi("get_health_summary", text(R.string.tool_ui_health_summary_951c0b), text(R.string.tool_ui_summarize_steps_sleep_exercise_and_body_metrics_6ff66f)),
                    ToolItemUi("wifi_credentials", text(R.string.tool_ui_wi_fi_password_80e9a4), text(R.string.tool_ui_read_the_network_credentials_saved_by_the_phone_96d43a)),
                    ToolItemUi("search_media", text(R.string.tool_ui_album_pictures_23bcc2), text(R.string.tool_ui_retrieve_pictures_by_file_name_or_album_path_c08236)),
                    ToolItemUi("search_audio", text(R.string.tool_ui_audio_file_1ccf2e), text(R.string.tool_ui_search_audio_by_title_filename_or_author_82e20d)),
                    ToolItemUi("search_recordings", text(R.string.tool_ui_system_recording_15eb19), text(R.string.tool_ui_retrieve_recording_files_from_system_media_libra_314c4d)),
                    ToolItemUi("search_files", text(R.string.tool_ui_share_files_a3b376), text(R.string.tool_ui_retrieve_documents_and_files_from_shared_storage_7d6193)),
                    ToolItemUi("search_calendar_events", text(R.string.tool_ui_calendar_events_970349), text(R.string.tool_ui_search_events_by_title_location_or_description_1afd77)),
                    ToolItemUi("search_contacts", text(R.string.tool_ui_address_book_9070cb), text(R.string.tool_ui_retrieve_contact_name_and_open_address_6dacc5)),
                    ToolItemUi("search_call_history", text(R.string.tool_ui_call_history_88e57b), text(R.string.tool_ui_retrieve_calls_by_number_or_contact_name_2ce431)),
                    ToolItemUi("search_messages", text(R.string.tool_ui_short_message_17e1a4), text(R.string.tool_ui_search_text_messages_by_sender_or_text_keywords_e14363)),
                    ToolItemUi("search_downloads", text(R.string.tool_ui_download_history_8494d7), text(R.string.tool_ui_retrieve_system_download_tasks_and_files_3301b9)),
                    ToolItemUi("search_notes", text(R.string.tool_ui_coloros_notes_6c324c), text(R.string.tool_ui_retrieve_notes_to_dos_and_text_content_e806d7)),
                    ToolItemUi("search_coloros_recordings", text(R.string.tool_ui_coloros_recording_a4e425), text(R.string.tool_ui_retrieve_normal_recordings_and_call_recordings_55c192)),
                    ToolItemUi("search_recording_summaries", text(R.string.tool_ui_recording_summary_2fe550), text(R.string.tool_ui_retrieve_transcribed_summaries_and_notes_associa_9cb00f)),
                    ToolItemUi("search_system_memories", text(R.string.tool_ui_coloros_system_memory_eff961), text(R.string.tool_ui_retrieve_collected_information_and_its_structure_9c1c71)),
                    ToolItemUi("search_saved_places", text(R.string.tool_ui_save_location_c29782), text(R.string.tool_ui_retrieve_location_information_from_system_memory_52ea48)),
                    ToolItemUi("search_personal_orders", text(R.string.tool_ui_personal_order_25e4c9), text(R.string.tool_ui_retrieve_takeout_shopping_express_delivery_ticke_f8d002)),
                    ToolItemUi("search_qq_chat_images", text(R.string.tool_ui_qq_chat_pictures_e21bf9), text(R.string.tool_ui_retrieve_recent_pictures_in_qq_chat_picture_cach_b8f009)),
                    ToolItemUi("search_wechat_chat_images", text(R.string.tool_ui_wechat_chat_pictures_72b268), text(R.string.tool_ui_retrieve_recent_pictures_in_wechat_chat_picture__ab66f7)),
                ),
            ),
            ToolGroupUi(
                id = "file_vision",
                title = text(R.string.state_document_vision_6a65a7),
                tools = listOf(
                    ToolItemUi("read_image", text(R.string.tool_ui_read_pictures_ae993b), text(R.string.tool_ui_read_pictures_of_known_paths_and_hand_them_over__7f9569)),
                ),
            ),
            ToolGroupUi(
                id = "planning",
                title = text(R.string.state_task_planning),
                tools = listOf(
                    ToolItemUi("todo_write", text(R.string.tool_todo_write), text(R.string.tool_todo_write_description)),
                    ToolItemUi("goal", text(R.string.tool_goal), text(R.string.tool_goal_description)),
                    ToolItemUi("tasks", text(R.string.tool_tasks), text(R.string.tool_tasks_description)),
                ),
            ),
            ToolGroupUi(
                id = "interaction",
                title = text(R.string.state_user_interaction),
                tools = listOf(
                    ToolItemUi("ask_user", text(R.string.tool_ask_user), text(R.string.tool_ask_user_description)),
                ),
            ),
            ToolGroupUi(
                id = "memory",
                title = text(R.string.state_memory_b55ff5),
                tools = listOf(
                    ToolItemUi("memory_get", text(R.string.tool_ui_read_memory_979135), text(R.string.tool_ui_paged_to_read_or_retrieve_long_term_memory_in_me_88afc4)),
                    ToolItemUi("memory_write", text(R.string.tool_ui_organize_memory_2b08eb), text(R.string.tool_ui_partially_update_append_or_clear_long_term_memor_c1bab6)),
                    ToolItemUi("character_memory_get", text(R.string.tool_ui_character_memory_get), text(R.string.tool_ui_character_memory_get_summary)),
                    ToolItemUi("character_memory_write", text(R.string.tool_ui_character_memory_write), text(R.string.tool_ui_character_memory_write_summary)),
                ),
            ),
            ToolGroupUi(
                id = "terminal",
                title = text(R.string.state_terminal_and_files_ae7c54),
                tools = listOf(
                    ToolItemUi("terminal", text(R.string.tool_ui_session_terminal_09c6e6), text(R.string.tool_ui_user_root_shell_conversational_execution_and_asy_13c2ab)),
                    ToolItemUi("terminal_job", text(R.string.tool_terminal_job), text(R.string.tool_terminal_job_description)),
                    ToolItemUi("terminal_daemon", text(R.string.tool_terminal_daemon), text(R.string.tool_terminal_daemon_description)),
                    ToolItemUi("edit_file", text(R.string.tool_edit_file), text(R.string.tool_edit_file_description)),
                    ToolItemUi("edit_files", text(R.string.tool_edit_files), text(R.string.tool_edit_files_description)),
                    ToolItemUi("list_file_backups", text(R.string.tool_list_file_backups), text(R.string.tool_list_file_backups_description)),
                    ToolItemUi("restore_file", text(R.string.tool_restore_file), text(R.string.tool_restore_file_description)),
                    ToolItemUi("stat_file", text(R.string.tool_stat_file), text(R.string.tool_stat_file_description)),
                    ToolItemUi("glob_files", text(R.string.tool_glob_files), text(R.string.tool_glob_files_description)),
                    ToolItemUi("grep_files", text(R.string.tool_grep_files), text(R.string.tool_grep_files_description)),
                    ToolItemUi("code_outline", text(R.string.tool_code_outline), text(R.string.tool_code_outline_description)),
                    ToolItemUi("read_file", text(R.string.tool_ui_read_file_dc995c), text(R.string.tool_ui_read_the_contents_of_mobile_phone_files_bf3066)),
                    ToolItemUi("write_file", text(R.string.tool_ui_write_file_e620fd), text(R.string.tool_ui_write_or_overwrite_mobile_files_29fae4)),
                    ToolItemUi("list_directory", text(R.string.tool_ui_list_directory_96e765), text(R.string.tool_ui_list_directory_contents_feff30)),
                    ToolItemUi("project_info", text(R.string.tool_project_info), text(R.string.tool_project_info_description)),
                ),
            ),
            ToolGroupUi(
                id = "plugins",
                title = text(R.string.state_plugins),
                tools = listOf(
                    ToolItemUi("plugin_list", text(R.string.tool_plugin_list), text(R.string.tool_plugin_list_description)),
                    ToolItemUi("plugin_install", text(R.string.tool_plugin_install), text(R.string.tool_plugin_install_description)),
                    ToolItemUi("plugin_uninstall", text(R.string.tool_plugin_uninstall), text(R.string.tool_plugin_uninstall_description)),
                    ToolItemUi("plugin_enable", text(R.string.tool_plugin_enable), text(R.string.tool_plugin_enable_description)),
                    ToolItemUi("plugin_reload", text(R.string.tool_plugin_reload), text(R.string.tool_plugin_reload_description)),
                ),
            ),
            ToolGroupUi(
                id = "acp_agents",
                title = text(R.string.state_acp_agents),
                tools = listOf(
                    ToolItemUi("acp_agent_list", text(R.string.tool_acp_agent_list), text(R.string.tool_acp_agent_list_description)),
                    ToolItemUi("acp_delegate", text(R.string.tool_acp_delegate), text(R.string.tool_acp_delegate_description)),
                    ToolItemUi("acp_session_status", text(R.string.tool_acp_session_status), text(R.string.tool_acp_session_status_description)),
                    ToolItemUi("acp_session_close", text(R.string.tool_acp_session_close), text(R.string.tool_acp_session_close_description)),
                ),
            ),
            ToolGroupUi(
                id = "virtual_display",
                title = text(R.string.state_virtual_display),
                tools = listOf(
                    ToolItemUi("virtual_display_create", text(R.string.tool_virtual_display_create), text(R.string.tool_virtual_display_create_description)),
                    ToolItemUi("virtual_display_list", text(R.string.tool_virtual_display_list), text(R.string.tool_virtual_display_list_description)),
                    ToolItemUi("virtual_display_launch", text(R.string.tool_virtual_display_launch), text(R.string.tool_virtual_display_launch_description)),
                    ToolItemUi("virtual_display_release", text(R.string.tool_virtual_display_release), text(R.string.tool_virtual_display_release_description)),
                ),
            ),
        )
    )
