#[derive(Debug, PartialEq, Eq)]
struct EnvironmentCheck {
    name: &'static str,
    version: u8,
}

fn main() {
    let check = EnvironmentCheck {
        name: "NTPX OmniBrowser M000",
        version: 1,
    };
    println!("{} validation v{}", check.name, check.version);
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn constructs_environment_check() {
        let check = EnvironmentCheck {
            name: "NTPX OmniBrowser M000",
            version: 1,
        };
        assert_eq!(check.version, 1);
        assert_eq!(check.name, "NTPX OmniBrowser M000");
    }
}
