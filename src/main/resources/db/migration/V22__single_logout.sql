-- Gemeinsames Abmelden (Back-Channel-Logout): Jede Anmeldesitzung bekommt eine öffentliche Kennung (sid), die im ID-Token
-- steht. Sie ist nicht das Cookie und nicht dessen Hash, aus ihr lässt sich keine Anmeldung ableiten.
ALTER TABLE login_sessions ADD COLUMN sid uuid NOT NULL DEFAULT gen_random_uuid();

-- Adresse, an die der Hub beim Abmelden das Logout-Token schickt. Ohne Adresse wird der Dienst nicht benachrichtigt
-- (seine Sitzung endet dann spätestens beim nächsten Refresh).
ALTER TABLE services ADD COLUMN backchannel_logout_uri text;
