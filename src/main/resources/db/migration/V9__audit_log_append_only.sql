-- Das Protokoll wird nur angehängt. Erlaubt ist allein das Leeren der IP-Adresse (nach 90 Tagen).
CREATE FUNCTION audit_log_guard() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'audit_log ist nur zum Anhängen da';
    END IF;
    IF NEW.ip IS NOT NULL
       OR (NEW.id, NEW.at, NEW.actor_id, NEW.subject_id, NEW.action, NEW.details)
          IS DISTINCT FROM (OLD.id, OLD.at, OLD.actor_id, OLD.subject_id, OLD.action, OLD.details) THEN
        RAISE EXCEPTION 'audit_log darf nur durch Leeren der IP geändert werden';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_log_guard_trigger
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION audit_log_guard();
