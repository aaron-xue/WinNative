package org.citron.citron_emu.wn

// Intent extras shared between the host app (com.winlator.cmod) and the :switch
// process. Kept here (in the citron module) so both sides reference the same
// constants without the app module having to depend on the host's RetroShortcuts.
object CitronConsts {
    const val EXTRA_ROM_PATH = "wn_citron_rom_path"
    const val EXTRA_GAME_NAME = "wn_citron_game_name"
    const val EXTRA_SHORTCUT_PATH = "wn_citron_shortcut_path"
    const val EXTRA_CONTAINER_ID = "wn_citron_container_id"

    // Content:// URIs (built by the host app's FileProvider) for the NSP/NSZ
    // files sitting next to the launched ROM, so the :switch process can register
    // DLC / update content as external content before boot. Supplied by CitronEmbedLaunch.
    const val EXTRA_ROM_SIBLINGS = "wn_citron_rom_siblings"

    // Authority of the host app's FileProvider (the same `tileprovider` PS2 uses).
    const val FILE_PROVIDER_AUTHORITY_SUFFIX = ".tileprovider"
}
