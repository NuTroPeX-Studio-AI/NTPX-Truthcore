fn main() {
    tauri::Builder::default()
        .run(tauri::generate_context!())
        .expect("error while running NTPX OmniBrowser M000 runner probe");
}
