package com.winlator.cmod.feature.retro

import android.content.Context
import com.winlator.cmod.R

enum class RetroOptionCategory {
    DISPLAY,
    SOUND,
    PERFORMANCE,
    CONTROLS,
    SYSTEM,
}

data class RetroCoreOption(
    val key: String,
    val label: String,
    val values: List<String>,
    val valueLabels: List<String>,
    val defaultValue: String,
    val category: RetroOptionCategory = RetroOptionCategory.SYSTEM,
    val advanced: Boolean = false,
    @androidx.annotation.StringRes val labelRes: Int? = null,
    val visibleWhen: Pair<String, String>? = null,
) {
    fun labelText(context: Context): String =
        labelRes?.let { context.getString(it) } ?: LABEL_LOOKUP[label]?.let { context.getString(it) } ?: label

    fun valueLabelText(context: Context, index: Int): String {
        val raw = valueLabels.getOrElse(index) { return "" }
        return VALUE_LABEL_LOOKUP[raw]?.let { context.getString(it) } ?: raw
    }

    fun isApplicable(current: (String) -> String?): Boolean {
        val (key, value) = visibleWhen ?: return true
        return (current(key) ?: return true) == value
    }

    companion object {
        private val LABEL_LOOKUP: Map<String, Int> = mapOf(
            "Arkanoid Mode" to R.string.retro_co_arkanoid_mode,
            "Auto" to R.string.retro_co_auto,
            "Mouse Sensitivity" to R.string.retro_co_mouse_sensitivity,
            "Show Zapper Crosshair" to R.string.retro_co_show_zapper_crosshair,
            "Turbo Delay (in frames)" to R.string.retro_co_turbo_delay,
            "Turbo Enable" to R.string.retro_co_turbo_enable,
            "Allow Opposing Directions" to R.string.retro_co_allow_opposing_directions,
            "Zapper Mode" to R.string.retro_co_zapper_mode,
            "Invert Zapper Sensor Signal" to R.string.retro_co_invert_zapper_sensor,
            "Zapper Tolerance" to R.string.retro_co_zapper_tolerance,
            "Invert Zapper Trigger Signal" to R.string.retro_co_invert_zapper_trigger,
            "NTSC Filter" to R.string.retro_co_ntsc_filter,
            "Crop Horizontal Left Overscan" to R.string.retro_co_crop_overscan_h_left,
            "Crop Horizontal Right Overscan" to R.string.retro_co_crop_overscan_h_right,
            "Crop Vertical Bottom Overscan" to R.string.retro_co_crop_overscan_v_bottom,
            "Crop Vertical Top Overscan" to R.string.retro_co_crop_overscan_v_top,
            "Color Palette" to R.string.retro_co_color_palette,
            "No Sprite Limit" to R.string.retro_co_no_sprite_limit,
            "Overclock" to R.string.retro_co_overclock,
            "RAM Power-On Fill (Restart Required)" to R.string.retro_co_ram_power_on_fill,
            "Audio Channel 1 (Square 1)" to R.string.retro_co_audio_channel_1,
            "Audio Channel 2 (Square 2)" to R.string.retro_co_audio_channel_2,
            "Audio Channel 3 (Triangle)" to R.string.retro_co_audio_channel_3,
            "Audio Channel 4 (Noise)" to R.string.retro_co_audio_channel_4,
            "Audio Channel 5 (PCM)" to R.string.retro_co_audio_channel_5,
            "Channel Volume (FDS)" to R.string.retro_co_channel_volume_fds,
            "Channel Volume (MMC5)" to R.string.retro_co_channel_volume_mmc5,
            "Channel Volume (N163)" to R.string.retro_co_channel_volume_n163,
            "Channel Volume (S5B)" to R.string.retro_co_channel_volume_s5b,
            "Channel Volume (VRC6)" to R.string.retro_co_channel_volume_vrc6,
            "Channel Volume (VRC7)" to R.string.retro_co_channel_volume_vrc7,
            "Reduce DMC Channel Popping" to R.string.retro_co_reduce_dmc_popping,
            "Reduce Triangle Channel Popping" to R.string.retro_co_reduce_triangle_popping,
            "Audio RF Filter" to R.string.retro_co_audio_rf_filter,
            "Sound Quality" to R.string.retro_co_sound_quality,
            "Sound Samplerate (Hint)" to R.string.retro_co_sound_samplerate,
            "Stereo Sound Effect" to R.string.retro_co_stereo_sound_effect,
            "Master Volume" to R.string.retro_co_master_volume,
            "Swap Audio Duty Cycles" to R.string.retro_co_swap_audio_duty_cycles,
            "Game Genie Add-On (Restart Required)" to R.string.retro_co_game_genie_addon,
            "Region" to R.string.retro_co_region,
            "Justifier 1 Color" to R.string.retro_co_justifier_1_color,
            "Justifier 1 Crosshair" to R.string.retro_co_justifier_1_crosshair,
            "Justifier 2 Color" to R.string.retro_co_justifier_2_color,
            "Justifier 2 Crosshair" to R.string.retro_co_justifier_2_crosshair,
            "Light Gun Mode" to R.string.retro_co_light_gun_mode,
            "M.A.C.S. Rifle Color" to R.string.retro_co_macs_rifle_color,
            "M.A.C.S. Rifle Crosshair" to R.string.retro_co_macs_rifle_crosshair,
            "Super Scope Color" to R.string.retro_co_super_scope_color,
            "Super Scope Crosshair" to R.string.retro_co_super_scope_crosshair,
            "Super Scope Reverse Trigger Buttons" to R.string.retro_co_super_scope_reverse_trigger,
            "Preferred Aspect Ratio" to R.string.retro_co_preferred_aspect_ratio,
            "Hi-Res Blending" to R.string.retro_co_hires_blending,
            "Crop Overscan" to R.string.retro_co_crop_overscan,
            "Block Invalid VRAM Access" to R.string.retro_co_block_invalid_vram_access,
            "Echo Buffer Hack (Unsafe, only enable for old addmusic hacks)" to R.string.retro_co_echo_buffer_hack,
            "Reduce Slowdown (Hack, Unsafe)" to R.string.retro_co_reduce_slowdown,
            "SuperFX Overclocking" to R.string.retro_co_superfx_overclocking,
            "Randomize Memory (Unsafe)" to R.string.retro_co_randomize_memory,
            "Reduce Flickering (Hack, Unsafe)" to R.string.retro_co_reduce_flickering,
            "Audio Interpolation" to R.string.retro_co_audio_interpolation,
            "Enable Graphic Clip Windows" to R.string.retro_co_graphic_clip_windows,
            "Enable Transparency Effects" to R.string.retro_co_transparency_effects,
            "Show Layer 1" to R.string.retro_co_show_layer_1,
            "Show Layer 2" to R.string.retro_co_show_layer_2,
            "Show Layer 3" to R.string.retro_co_show_layer_3,
            "Show Layer 4" to R.string.retro_co_show_layer_4,
            "Show Sprite Layer" to R.string.retro_co_show_sprite_layer,
            "Volume % for Sound Channel 1" to R.string.retro_co_sndchan_volume_1,
            "Volume % for Sound Channel 2" to R.string.retro_co_sndchan_volume_2,
            "Volume % for Sound Channel 3" to R.string.retro_co_sndchan_volume_3,
            "Volume % for Sound Channel 4" to R.string.retro_co_sndchan_volume_4,
            "Volume % for Sound Channel 5" to R.string.retro_co_sndchan_volume_5,
            "Volume % for Sound Channel 6" to R.string.retro_co_sndchan_volume_6,
            "Volume % for Sound Channel 7" to R.string.retro_co_sndchan_volume_7,
            "Volume % for Sound Channel 8" to R.string.retro_co_sndchan_volume_8,
            "Console Region (Reload Core)" to R.string.retro_co_console_region,
            "Allow Opposing Directional Input" to R.string.retro_co_allow_opposing_directional_input,
            "Audio Filter" to R.string.retro_co_audio_filter,
            "Audio Filter Level" to R.string.retro_co_audio_filter_level,
            "Audio Resampler" to R.string.retro_co_audio_resampler,
            "BIOS" to R.string.retro_co_bios_file,
            "Boot mode" to R.string.retro_co_boot_mode,
            "Borders" to R.string.retro_co_borders,
            "Cartridge Lock-On" to R.string.retro_co_cartridge_lock_on,
            "CD Access Time" to R.string.retro_co_cd_access_time,
            "CD add-on (MD mode) (Requires Restart)" to R.string.retro_co_cd_addon_md_mode,
            "CD Backup Cart BRAM (Requires Restart)" to R.string.retro_co_cd_backup_bram,
            "CD Backup Cart BRAM Size (Requires Restart)" to R.string.retro_co_cd_backup_bram_size,
            "CD Image Cache" to R.string.retro_co_cd_image_cache,
            "CD System BRAM (Requires Restart)" to R.string.retro_co_cd_system_bram,
            "CD-DA Volume" to R.string.retro_co_cdda_volume,
            "Color Correction" to R.string.retro_co_color_correction,
            "Color Correction - Frontlight Position" to R.string.retro_co_color_correction_frontlight,
            "Color Correction Mode" to R.string.retro_co_color_correction_mode,
            "Controller Rumble Strength" to R.string.retro_co_controller_rumble_strength,
            "Core-Provided Aspect Ratio" to R.string.retro_co_core_provided_aspect_ratio,
            "CPU Speed" to R.string.retro_co_cpu_speed,
            "Dark Filter Level (%)" to R.string.retro_co_dark_filter_level,
            "Default Game Boy Palette" to R.string.retro_co_default_gb_palette,
            "Dynamic Recompiler" to R.string.retro_co_dynamic_recompiler,
            "Emulated Hardware (Restart Required)" to R.string.retro_co_emulated_hardware,
            "Enhanced per-tile vertical scroll" to R.string.retro_co_enhanced_per_tile_vscroll,
            "Enhanced per-tile vertical scroll limit" to R.string.retro_co_enhanced_per_tile_vscroll_limit,
            "EQ High" to R.string.retro_co_eq_high,
            "EQ Low" to R.string.retro_co_eq_low,
            "EQ Mid" to R.string.retro_co_eq_mid,
            "FM Preamp Level" to R.string.retro_co_fm_preamp_level,
            "Force VDP Mode" to R.string.retro_co_force_vdp_mode,
            "Frameskip" to R.string.retro_co_frameskip,
            "Frameskip Interval" to R.string.retro_co_frameskip_interval,
            "Frameskip Threshold (%)" to R.string.retro_co_frameskip_threshold,
            "Game Gear Extended Screen" to R.string.retro_co_game_gear_extended_screen,
            "Game Link Mode" to R.string.retro_co_game_link_mode,
            "GB Colorization" to R.string.retro_co_gb_colorization,
            "Game Boy Model (Restart)" to R.string.retro_co_gb_model,
            "Game Boy Player Rumble (Restart)" to R.string.retro_co_gb_player_rumble,
            "Hide Master System Side Borders" to R.string.retro_co_hide_ms_side_borders,
            "Hardware Preset Game Boy Palettes (Restart)" to R.string.retro_co_hw_preset_gb_palettes,
            "Idle Loop Removal" to R.string.retro_co_idle_loop_removal,
            "Interframe Blending" to R.string.retro_co_interframe_blending,
            "Interlaced Mode 2 Output" to R.string.retro_co_interlaced_mode2,
            "Internal Palette" to R.string.retro_co_internal_palette,
            "Invert Mouse Y-Axis" to R.string.retro_co_invert_mouse_y_axis,
            "LCD Ghosting Filter" to R.string.retro_co_lcd_ghosting_filter,
            "Light Gun Input" to R.string.retro_co_light_gun_input,
            "Link Cable Connectivity" to R.string.retro_co_link_cable_connectivity,
            "Low-Pass Filter %" to R.string.retro_co_low_pass_filter,
            "Mega Drive/Genesis FM" to R.string.retro_co_md_genesis_fm,
            "Mega Drive/Genesis FM Channel 0 Volume %" to R.string.retro_co_md_fm_ch_vol_0,
            "Mega Drive/Genesis FM Channel 1 Volume %" to R.string.retro_co_md_fm_ch_vol_1,
            "Mega Drive/Genesis FM Channel 2 Volume %" to R.string.retro_co_md_fm_ch_vol_2,
            "Mega Drive/Genesis FM Channel 3 Volume %" to R.string.retro_co_md_fm_ch_vol_3,
            "Mega Drive/Genesis FM Channel 4 Volume %" to R.string.retro_co_md_fm_ch_vol_4,
            "Mega Drive/Genesis FM Channel 5 Volume %" to R.string.retro_co_md_fm_ch_vol_5,
            "Master System FM (YM2413) Channel 0 Volume %" to R.string.retro_co_ms_fm_ch_vol_0,
            "Master System FM (YM2413) Channel 1 Volume %" to R.string.retro_co_ms_fm_ch_vol_1,
            "Master System FM (YM2413) Channel 2 Volume %" to R.string.retro_co_ms_fm_ch_vol_2,
            "Master System FM (YM2413) Channel 3 Volume %" to R.string.retro_co_ms_fm_ch_vol_3,
            "Master System FM (YM2413) Channel 4 Volume %" to R.string.retro_co_ms_fm_ch_vol_4,
            "Master System FM (YM2413) Channel 5 Volume %" to R.string.retro_co_ms_fm_ch_vol_5,
            "Master System FM (YM2413) Channel 6 Volume %" to R.string.retro_co_ms_fm_ch_vol_6,
            "Master System FM (YM2413) Channel 7 Volume %" to R.string.retro_co_ms_fm_ch_vol_7,
            "Master System FM (YM2413) Channel 8 Volume %" to R.string.retro_co_ms_fm_ch_vol_8,
            "Master System FM (YM2413)" to R.string.retro_co_ms_fm_ym2413,
            "Master System FM (YM2413) Core" to R.string.retro_co_ms_fm_ym2413_core,
            "Network Link Port" to R.string.retro_co_network_link_port,
            "PCM Volume" to R.string.retro_co_pcm_volume,
            "PSG Noise Channel 3 Volume %" to R.string.retro_co_psg_noise_ch3_vol,
            "PSG Preamp Level" to R.string.retro_co_psg_preamp_level,
            "PSG Tone Channel 0 Volume %" to R.string.retro_co_psg_tone_ch_vol_0,
            "PSG Tone Channel 1 Volume %" to R.string.retro_co_psg_tone_ch_vol_1,
            "PSG Tone Channel 2 Volume %" to R.string.retro_co_psg_tone_ch_vol_2,
            "Remove Per-Line Sprite Limit" to R.string.retro_co_remove_per_line_sprite_limit,
            "RTC support" to R.string.retro_co_rtc_support,
            "Rumble support" to R.string.retro_co_rumble_support,
            "Show Light Gun Crosshair" to R.string.retro_co_show_light_gun_crosshair,
            "Skip BIOS Intro (Restart)" to R.string.retro_co_skip_bios_intro,
            "Solar Sensor Level" to R.string.retro_co_solar_sensor_level,
            "Sound Output" to R.string.retro_co_sound_output,
            "Sound Output Rate (Hz)" to R.string.retro_co_sound_output_rate,
            "System Boot ROM" to R.string.retro_co_system_boot_rom,
            "System Hardware" to R.string.retro_co_system_hardware,
            "System Lock-Ups" to R.string.retro_co_system_lockups,
            "System Region" to R.string.retro_co_system_region,
            "Turbo Button Period" to R.string.retro_co_turbo_button_period,
            "Use BIOS File if Found (Restart)" to R.string.retro_co_use_bios_file,
            "Use Official Bootloader (Restart Required)" to R.string.retro_co_use_official_bootloader,
            "Use Super Game Boy Borders (Restart)" to R.string.retro_co_use_super_gb_borders,
            "Adaptive Smoothing" to R.string.retro_co_adaptive_smoothing,
            "Additional Cropping" to R.string.retro_co_additional_cropping,
            "Analog Deadzone (percent)" to R.string.retro_co_analog_deadzone,
            "Analog Sensitivity (percent)" to R.string.retro_co_analog_sensitivity,
            "Analog Self-Calibration" to R.string.retro_co_analog_self_calibration,
            "Background Mode" to R.string.retro_co_background_mode,
            "Bilinear filtering mode" to R.string.retro_co_bilinear_filtering_mode,
            "Cache GPU Shaders" to R.string.retro_co_cache_gpu_shaders,
            "Cache Textures" to R.string.retro_co_cache_textures,
            "CD Access Method" to R.string.retro_co_cd_access_method,
            "CD Loading Speed" to R.string.retro_co_cd_loading_speed,
            "Color buffer to RDRAM" to R.string.retro_co_color_buffer_to_rdram,
            "Continuous texrect coords" to R.string.retro_co_continuous_texrect_coords,
            "Copy auxiliary buffers to RDRAM" to R.string.retro_co_copy_aux_buffers_rdram,
            "Core Aspect Ratio" to R.string.retro_co_core_aspect_ratio,
            "Core-Reported FPS Timing" to R.string.retro_co_core_reported_fps_timing,
            "Count Per Op" to R.string.retro_co_count_per_op,
            "Count Per Op Divider (Overclock)" to R.string.retro_co_count_per_op_divider,
            "CPU Core" to R.string.retro_co_cpu_core_n64,
            "CPU Dynarec" to R.string.retro_co_cpu_dynarec,
            "CPU Frequency Scaling (Overclock)" to R.string.retro_co_cpu_frequency_scaling,
            "Deinterlace Method" to R.string.retro_co_deinterlace_method,
            "Depth buffer to RDRAM" to R.string.retro_co_depth_buffer_to_rdram,
            "Disable Expansion Pak" to R.string.retro_co_disable_expansion_pak,
            "Display Full VRAM (Debug)" to R.string.retro_co_display_full_vram,
            "Display Internal FPS" to R.string.retro_co_display_internal_fps,
            "Display OSD Messages" to R.string.retro_co_display_osd_messages,
            "Dithering" to R.string.retro_co_dithering,
            "Dithering Pattern" to R.string.retro_co_dithering_pattern,
            "Dithering Quantization" to R.string.retro_co_dithering_quantization,
            "Don\'t filter background textures" to R.string.retro_co_dont_filter_bg_textures,
            "Down C Button" to R.string.retro_co_down_c_button,
            "DualShock Analog Mode Combo" to R.string.retro_co_dualshock_analog_mode_combo,
            "DualShock Analog Mode Combo Hold Delay" to R.string.retro_co_dualshock_analog_mode_combo_hold,
            "DualShock Analog Mode Toggle" to R.string.retro_co_dualshock_analog_mode_toggle,
            "Dump Textures" to R.string.retro_co_dump_textures,
            "Dynarec Code Invalidation" to R.string.retro_co_dynarec_code_invalidation,
            "Dynarec Cycles Per Instruction" to R.string.retro_co_dynarec_cpi,
            "Dynarec DMA/GPU/MDEC/Timer Event Cycles" to R.string.retro_co_dynarec_dma_gpu_event,
            "Dynarec SP GP Hit RAM Optimization" to R.string.retro_co_dynarec_sp_gp_hit,
            "Dynarec SPU Samples" to R.string.retro_co_dynarec_spu_samples,
            "Enable color buffer copy from RDRAM" to R.string.retro_co_enable_color_buffer_rdram,
            "Enable inaccurate texture coordinates" to R.string.retro_co_enable_inaccurate_tex_coords,
            "Use enhanced Hi-Res Storage" to R.string.retro_co_enhanced_high_res_storage,
            "Use enhanced Texture Storage" to R.string.retro_co_enhanced_texture_storage,
            "Exclude 2D Polygons from Filtering" to R.string.retro_co_exclude_2d_filtering,
            "Exclude Sprites from Filtering" to R.string.retro_co_exclude_sprites_filtering,
            "Frame Duping" to R.string.retro_co_frame_duping,
            "Frame Duplication" to R.string.retro_co_frame_duplication,
            "Framebuffer Emulation" to R.string.retro_co_framebuffer_emulation,
            "Framerate" to R.string.retro_co_framerate,
            "FXAA" to R.string.retro_co_fxaa,
            "GPU Rasterizer Overclock" to R.string.retro_co_gpu_rasterizer_overclock,
            "GPU shader depth write" to R.string.retro_co_gpu_shader_depth_write,
            "GTE Overclock" to R.string.retro_co_gte_overclock,
            "Gun Crosshair Color: Port 1" to R.string.retro_co_gun_crosshair_color_p1,
            "Gun Crosshair Color: Port 2" to R.string.retro_co_gun_crosshair_color_p2,
            "Gun Cursor" to R.string.retro_co_gun_cursor,
            "Gun Input Mode" to R.string.retro_co_gun_input_mode,
            "Hardware per-pixel lighting" to R.string.retro_co_hardware_pixel_lighting,
            "Hide overscan" to R.string.retro_co_hide_overscan,
            "Use alternative method for High-Res Checksums" to R.string.retro_co_high_res_checksums_alt,
            "Horizontal Image Offset (GPU Cycles)" to R.string.retro_co_horizontal_image_offset,
            "Hybrid Filter" to R.string.retro_co_hybrid_filter,
            "Ignore emulated TLB Exceptions" to R.string.retro_co_ignore_emulated_tlb,
            "Image Dithering Mode" to R.string.retro_co_image_dithering_mode,
            "Independent C-button Controls" to R.string.retro_co_independent_c_button,
            "INI Behaviour" to R.string.retro_co_ini_behaviour,
            "Initial Scan Line - NTSC" to R.string.retro_co_initial_scanline_ntsc,
            "Initial Scan Line - PAL" to R.string.retro_co_initial_scanline_pal,
            "Internal Color Depth" to R.string.retro_co_internal_color_depth,
            "Internal GPU Resolution" to R.string.retro_co_internal_gpu_resolution,
            "Last Scan Line - NTSC" to R.string.retro_co_last_scanline_ntsc,
            "Last Scan Line - PAL" to R.string.retro_co_last_scanline_pal,
            "Left C Button" to R.string.retro_co_left_c_button,
            "Less accurate blending mode" to R.string.retro_co_less_accurate_blending,
            "Line-to-Quad Hack" to R.string.retro_co_line_to_quad_hack,
            "LOD Emulation" to R.string.retro_co_lod_emulation,
            "Max High-Res VRAM Limit" to R.string.retro_co_max_high_res_vram,
            "Max texture cache size" to R.string.retro_co_max_texture_cache_size,
            "MDEC YUV Chroma Filter" to R.string.retro_co_mdec_yuv_chroma_filter,
            "Memory Card Method" to R.string.retro_co_memory_card_method,
            "Memory Card Slot 1 Index" to R.string.retro_co_memory_card_slot1_index,
            "Memory Card Slot 2" to R.string.retro_co_memory_card_slot2,
            "Memory Card Slot 2 Index" to R.string.retro_co_memory_card_slot2_index,
            "MSAA level" to R.string.retro_co_msaa_level,
            "Multi-Sampled Anti Aliasing" to R.string.retro_co_multi_sampled_aa,
            "Multi-threading" to R.string.retro_co_multithreading,
            "Multitap on Port 1" to R.string.retro_co_multitap_port1,
            "Multitap on Port 2" to R.string.retro_co_multitap_port2,
            "N64 Depth Compare" to R.string.retro_co_n64_depth_compare,
            "Native res. 2D texrects" to R.string.retro_co_native_res_2d_texrects,
            "Enable native-res boundaries for texture coordinates" to R.string.retro_co_native_res_boundaries,
            "Native Resolution Factor" to R.string.retro_co_native_resolution_factor,
            "Offset Cropped Image" to R.string.retro_co_offset_cropped_image,
            "Override BIOS" to R.string.retro_co_override_bios,
            "Overscan" to R.string.retro_co_overscan,
            "Overscan Offset (Bottom)" to R.string.retro_co_overscan_offset_bottom,
            "Overscan Offset (Left)" to R.string.retro_co_overscan_offset_left,
            "Overscan Offset (Right)" to R.string.retro_co_overscan_offset_right,
            "Overscan Offset (Top)" to R.string.retro_co_overscan_offset_top,
            "PAL Video Timing Override" to R.string.retro_co_pal_video_timing,
            "PGXP 2D Geometry Tolerance" to R.string.retro_co_pgxp_2d_geometry,
            "PGXP Operation Mode" to R.string.retro_co_pgxp_operation_mode,
            "PGXP Perspective Correct Texturing" to R.string.retro_co_pgxp_perspective_texturing,
            "PGXP Primitive Culling" to R.string.retro_co_pgxp_primitive_culling,
            "PGXP Vertex Cache" to R.string.retro_co_pgxp_vertex_cache,
            "Player 1 Pak" to R.string.retro_co_player_pak_1,
            "Player 2 Pak" to R.string.retro_co_player_pak_2,
            "Player 3 Pak" to R.string.retro_co_player_pak_3,
            "Player 4 Pak" to R.string.retro_co_player_pak_4,
            "RDP Plugin" to R.string.retro_co_rdp_plugin,
            "Replace Textures" to R.string.retro_co_replace_textures,
            "Right C Button" to R.string.retro_co_right_c_button,
            "RSP Plugin" to R.string.retro_co_rsp_plugin,
            "Shared Memory Cards" to R.string.retro_co_shared_memory_cards,
            "Skip BIOS" to R.string.retro_co_skip_bios,
            "Software Framebuffer" to R.string.retro_co_software_framebuffer,
            "SPU Silent Voice Optimization" to R.string.retro_co_spu_silent_voice,
            "Supersampling (Downsample to Native Resolution)" to R.string.retro_co_supersampling,
            "Texture Enhancement" to R.string.retro_co_texture_enhancement,
            "Texture filter" to R.string.retro_co_texture_filter,
            "Texture Filtering" to R.string.retro_co_texture_filtering,
            "Texture UV Offset" to R.string.retro_co_texture_uv_offset,
            "Thread sync level" to R.string.retro_co_thread_sync_level,
            "Threaded Renderer" to R.string.retro_co_threaded_renderer,
            "Track Textures" to R.string.retro_co_track_textures,
            "Up C Button" to R.string.retro_co_up_c_button,
            "Use High-Res Full Alpha Channel" to R.string.retro_co_use_high_res_full_alpha,
            "Use High-Res Texture Cache Compression" to R.string.retro_co_use_high_res_texture_cache,
            "Use High-Res textures" to R.string.retro_co_use_high_res_textures,
            "VI Overlay" to R.string.retro_co_vi_overlay,
            "VI Refresh (Overclock)" to R.string.retro_co_vi_refresh,
            "Wide Resolution" to R.string.retro_co_wide_resolution,
            "Widescreen Mode Hack" to R.string.retro_co_widescreen_mode_hack,
            "Widescreen Mode Hack Aspect Ratio" to R.string.retro_co_widescreen_mode_hack_ar,
        )

        private val VALUE_LABEL_LOOKUP: Map<String, Int> = mapOf(
            "enabled" to R.string.retro_co_val_on,
            "disabled" to R.string.retro_co_val_off,
            "On" to R.string.retro_co_val_on,
            "Off" to R.string.retro_co_val_off,
            "Hardware" to R.string.retro_co_val_hardware,
            "Software" to R.string.retro_co_val_software,
            "JIT" to R.string.retro_co_val_jit,
            "Cached Interpreter" to R.string.retro_co_val_cached_interpreter,
            "Interpreter" to R.string.retro_co_val_interpreter,
            "Auto" to R.string.retro_co_val_auto,
            "None" to R.string.retro_co_val_none,
            "Player 1" to R.string.retro_co_val_player1,
            "Player 2" to R.string.retro_co_val_player2,
            "Both" to R.string.retro_co_val_both,
            "Low" to R.string.retro_co_val_low,
            "High" to R.string.retro_co_val_high,
            "Very High" to R.string.retro_co_val_very_high,
            "Touchscreen" to R.string.retro_co_val_touchscreen,
            "Mouse" to R.string.retro_co_val_mouse,
            "Light Gun" to R.string.retro_co_val_light_gun,
            "Absolute mouse" to R.string.retro_co_val_absolute_mouse,
            "Pixel Perfect" to R.string.retro_co_val_pixel_perfect,
            "Uncorrected" to R.string.retro_co_val_uncorrected,
            "4:3 (Preserved)" to R.string.retro_co_val_4_3_preserved,
            "Not Connected" to R.string.retro_co_val_not_connected,
            "Network Server" to R.string.retro_co_val_net_server,
            "Network Client" to R.string.retro_co_val_net_client,
            "Crosshair light gun" to R.string.retro_co_val_crosshair_light_gun,
            "Sequential Targets light gun" to R.string.retro_co_val_sequential_light_gun,
            "Stretch" to R.string.retro_co_val_stretch,
            "Bottom" to R.string.retro_co_val_bottom,
            "Top" to R.string.retro_co_val_top,
            "Native" to R.string.retro_co_val_native,
            "Internal" to R.string.retro_co_val_internal,
            "1x" to R.string.retro_co_val_1x,
            "2x" to R.string.retro_co_val_2x,
            "4x" to R.string.retro_co_val_4x,
            "8:7 PAR" to R.string.retro_co_val_8_7_native,
            "4:3" to R.string.retro_co_val_4_3,
            "16:9" to R.string.retro_co_val_16_9,
            "On (16:9)" to R.string.retro_co_val_on_16_9,
            "GBA Screen" to R.string.retro_co_val_gba_screen,
            "GBC Screen" to R.string.retro_co_val_gbc_screen,
            "320x240 (Native)" to R.string.retro_co_val_320x240_native,
            "640x480" to R.string.retro_co_val_640x480,
            "960x720" to R.string.retro_co_val_960x720,
            "1280x960" to R.string.retro_co_val_1280x960,
            "Hardware (OpenGL ES 3)" to R.string.retro_co_val_hardware,
            "Hardware (Vulkan)" to R.string.retro_co_val_hardware,
        )
    }
}

object RetroCoreOptions {
    private val DOLPHIN_COMMON_OPTIONS =
        listOf(
            RetroCoreOption(
                key = "dolphin_renderer",
                label = "Renderer",
                values = listOf("Hardware", "Software"),
                valueLabels = listOf("Hardware", "Software"),
                defaultValue = "Hardware",
                category = RetroOptionCategory.DISPLAY,
                labelRes = R.string.retro_co_renderer,
            ),
            RetroCoreOption(
                key = "dolphin_efb_scale",
                label = "Internal Resolution",
                values = listOf("1", "2", "3", "4", "5", "6"),
                valueLabels = listOf("1x", "2x", "3x", "4x", "5x", "6x"),
                defaultValue = "1",
                category = RetroOptionCategory.DISPLAY,
                labelRes = R.string.retro_co_internal_resolution,
            ),
        )

    private fun dolphinAspectOption(defaultValue: String) =
        RetroCoreOption(
            key = "dolphin_aspect_ratio",
            label = "Aspect Ratio",
            values = listOf("0", "1", "2", "3"),
            valueLabels = listOf("Auto", "16:9", "4:3", "Stretch"),
            defaultValue = defaultValue,
            category = RetroOptionCategory.DISPLAY,
            labelRes = R.string.retro_co_aspect_ratio,
        )

    private val DOLPHIN_CPU_OPTIONS =
        listOf(
            RetroCoreOption(
                key = "dolphin_cpu_core",
                label = "CPU Core",
                values = listOf("4", "5", "0"),
                valueLabels = listOf("JIT", "Cached Interpreter", "Interpreter"),
                defaultValue = "4",
                category = RetroOptionCategory.PERFORMANCE,
                labelRes = R.string.retro_co_cpu_core,
            ),
            RetroCoreOption(
                key = "dolphin_main_cpu_thread",
                label = "Dual Core",
                values = listOf("disabled", "enabled"),
                valueLabels = listOf("Off", "On"),
                defaultValue = "disabled",
                category = RetroOptionCategory.PERFORMANCE,
                labelRes = R.string.retro_co_dual_core,
            ),
        )

    private val DOLPHIN_CHEATS_OPTION =
        RetroCoreOption(
            key = "dolphin_cheats_enabled",
            label = "Internal Cheats",
            values = listOf("disabled", "enabled"),
            valueLabels = listOf("Off", "On"),
            defaultValue = "disabled",
            category = RetroOptionCategory.SYSTEM,
            labelRes = R.string.retro_co_internal_cheats,
        )

    private val DOLPHIN_VI_SKIP_OPTION =
        RetroCoreOption(
            key = "dolphin_vi_skip",
            label = "VBI Skip",
            values = listOf("enabled", "disabled"),
            valueLabels = listOf("On", "Off"),
            defaultValue = "enabled",
            category = RetroOptionCategory.PERFORMANCE,
            labelRes = R.string.retro_co_vbi_skip,
        )

    private val GAMECUBE_OPTIONS =
        DOLPHIN_COMMON_OPTIONS +
            dolphinAspectOption("2") +
            listOf(
                RetroCoreOption(
                    key = "dolphin_widescreen_hack",
                    label = "Widescreen Hack",
                    values = listOf("disabled", "enabled"),
                    valueLabels = listOf("Off", "On (16:9)"),
                    defaultValue = "disabled",
                    category = RetroOptionCategory.DISPLAY,
                    labelRes = R.string.retro_co_widescreen_hack,
                ),
            ) +
            DOLPHIN_CPU_OPTIONS +
            listOf(
                RetroCoreOption(
                    key = "dolphin_skip_gc_bios",
                    label = "Skip GameCube BIOS",
                    values = listOf("enabled", "disabled"),
                    valueLabels = listOf("On", "Off"),
                    defaultValue = "enabled",
                    category = RetroOptionCategory.SYSTEM,
                    labelRes = R.string.retro_co_skip_gc_bios,
                ),
                DOLPHIN_VI_SKIP_OPTION,
                DOLPHIN_CHEATS_OPTION,
            )

    private val WII_OPTIONS =
        DOLPHIN_COMMON_OPTIONS +
            dolphinAspectOption("1") +
            listOf(
                RetroCoreOption(
                    key = "dolphin_widescreen",
                    label = "Widescreen",
                    values = listOf("enabled", "disabled"),
                    valueLabels = listOf("On", "Off"),
                    defaultValue = "enabled",
                    category = RetroOptionCategory.DISPLAY,
                    labelRes = R.string.retro_co_widescreen,
                ),
            ) +
            DOLPHIN_CPU_OPTIONS +
            listOf(
                RetroCoreOption(
                    key = "dolphin_sensor_bar_position",
                    label = "Sensor Bar Position",
                    values = listOf("0", "1"),
                    valueLabels = listOf("Bottom", "Top"),
                    defaultValue = "0",
                    category = RetroOptionCategory.CONTROLS,
                    labelRes = R.string.retro_co_sensor_bar,
                ),
                DOLPHIN_VI_SKIP_OPTION,
                DOLPHIN_CHEATS_OPTION,
            )

    fun sanitizeDolphinVariables(vars: MutableMap<String, String>) {
        when (vars["dolphin_cpu_core"]?.trim()) {
            "JITARM64", "JIT", "JIT64" -> vars["dolphin_cpu_core"] = "4"
            "Cached Interpreter", "CachedInterpreter" -> vars["dolphin_cpu_core"] = "5"
            "Interpreter" -> vars["dolphin_cpu_core"] = "0"
        }
        vars["dolphin_efb_scale"]?.let { raw ->
            val t = raw.trim()
            if (t.length == 1 && t[0].isDigit()) return@let
            val digit =
                Regex("""^x?(\d+)""", RegexOption.IGNORE_CASE).find(t)?.groupValues?.getOrNull(1)
            if (digit != null) vars["dolphin_efb_scale"] = digit
        }
        DOLPHIN_PERF_DEFAULTS.forEach { (k, v) ->
            if (vars[k].isNullOrBlank()) vars[k] = v
        }
        if (vars["dolphin_skip_dupe_frames"] == "enabled") {
            vars["dolphin_skip_dupe_frames"] = "disabled"
        }
    }

    private val DOLPHIN_PERF_DEFAULTS: Map<String, String> =
        mapOf(
            "dolphin_renderer" to "Hardware",
            "dolphin_efb_scale" to "1",
            "dolphin_cpu_core" to "4",
            "dolphin_main_cpu_thread" to "disabled",
            "dolphin_dsp_hle" to "enabled",
            "dolphin_fast_disc_speed" to "enabled",
            "dolphin_vi_skip" to "disabled",
            "dolphin_skip_dupe_frames" to "disabled",
            "dolphin_cheats_enabled" to "disabled",
        )

    fun defaultVariables(system: RetroSystem?): Map<String, String> =
        when (system?.id) {
            RetroSystems.N64.id ->
                mapOf(
                    "mupen64plus-43screensize" to "640x480",
                    "mupen64plus-EnableFBEmulation" to "True",
                    "mupen64plus-aspect" to "4:3",
                )
            RetroSystems.PSX.id ->
                mapOf(
                    "beetle_psx_skip_bios" to "enabled",
                )
            RetroSystems.GAMECUBE.id ->
                DOLPHIN_PERF_DEFAULTS +
                    mapOf(
                        "dolphin_widescreen" to "disabled",
                        "dolphin_widescreen_hack" to "disabled",
                        "dolphin_aspect_ratio" to "2",
                        "dolphin_skip_gc_bios" to "enabled",
                    )
            RetroSystems.WII.id ->
                DOLPHIN_PERF_DEFAULTS +
                    mapOf(
                        "dolphin_widescreen" to "enabled",
                        "dolphin_aspect_ratio" to "1",
                        "dolphin_sensor_bar_position" to "0",
                    )
            else -> emptyMap()
        }

    fun forSystem(system: RetroSystem?): List<RetroCoreOption> =
        when (system?.id) {
            RetroSystems.NES.id -> RetroCoreCatalog.FCEUMM
            RetroSystems.SNES.id -> RetroCoreCatalog.SNES9X
            RetroSystems.GAMEBOY.id, RetroSystems.GAMEBOY_COLOR.id -> RetroCoreCatalog.GAMBATTE
            RetroSystems.GBA.id -> RetroCoreCatalog.MGBA
            RetroSystems.GENESIS.id -> forGenesisFamily(RetroSystems.GENESIS.id)
            RetroSystems.MASTER_SYSTEM.id -> forGenesisFamily(RetroSystems.MASTER_SYSTEM.id)
            RetroSystems.GAME_GEAR.id -> forGenesisFamily(RetroSystems.GAME_GEAR.id)
            RetroSystems.N64.id -> RetroCoreCatalog.MUPEN64PLUS_NEXT
            RetroSystems.PSX.id -> RetroCoreCatalog.BEETLE_PSX
            RetroSystems.GAMECUBE.id -> GAMECUBE_OPTIONS
            RetroSystems.WII.id -> WII_OPTIONS
            else -> emptyList()
        }

    private fun forGenesisFamily(systemId: String): List<RetroCoreOption> {
        val promote =
            when (systemId) {
                RetroSystems.MASTER_SYSTEM.id ->
                    setOf(
                        "genesis_plus_gx_ym2413",
                        "genesis_plus_gx_ym2413_core",
                        "genesis_plus_gx_left_border",
                    )
                RetroSystems.GAME_GEAR.id ->
                    setOf(
                        "genesis_plus_gx_gg_extra",
                        "genesis_plus_gx_lcd_filter",
                    )
                else -> emptySet()
            }
        val demote =
            when (systemId) {
                RetroSystems.GENESIS.id ->
                    setOf(
                        "genesis_plus_gx_ym2413",
                        "genesis_plus_gx_ym2413_core",
                        "genesis_plus_gx_left_border",
                        "genesis_plus_gx_gg_extra",
                    )
                else -> emptySet()
            }
        return RetroCoreCatalog.GENESIS_PLUS_GX.map { option ->
            when (option.key) {
                in promote -> option.copy(advanced = false)
                in demote -> option.copy(advanced = true)
                else -> option
            }
        }
    }
}